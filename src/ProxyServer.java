import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * HTTP proxy.
 *
 * This proxy supports basic HTTP request forwarding using
 * the CONNECT method. It does not implement caching, authentication or
 * filtering. The implementation is intentionally simple to illustrate the
 * high-level behaviour of a proxy.
 */
public class ProxyServer {
    /** Port where the proxy listens for incoming client connections. */
    private final int listenPort;

    /** Running flag to allow graceful stop of the listening loop. */
    private volatile boolean running = true;

    /**
     * Create a proxy server that listens on the given port.
     *
     * @param listenPort TCP port to bind the server socket to
     */
    public ProxyServer(int listenPort) {
        this.listenPort = listenPort;
    }

    /**
     * Stop the proxy accept loop. The current implementation will exit the
     * accept loop and close the server socket when start() next checks the
     * running flag.
     */
    @SuppressWarnings("unused")
    public void stop() { running = false; }

    /**
     * Start the proxy server. This method blocks and accepts incoming
     * connections. Each accepted connection is handled in its own thread by
     * a ClientHandler instance.
     *
     * @throws IOException if binding the server socket fails
     */
    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(listenPort)) {
            System.out.println("Proxy en écoute sur port " + listenPort);
            while (running) {
                Socket client = serverSocket.accept();
                client.setSoTimeout(30_000);
                new Thread(new ClientHandler(client), "ProxyClient-" + client.getPort()).start();
            }
        }
    }

    /**
     * Inner class responsible for handling a single client connection.
     *
     * The handler reads the initial request line and headers from the client
     * and either performs a CONNECT tunnel (for HTTPS) or forwards a normal
     * HTTP request to the remote server.
     */
    private static class ClientHandler implements Runnable {
        private final Socket client;

        /**
         * Construct a handler for the given client socket.
         *
         * @param client accepted client socket
         */
        ClientHandler(Socket client) { this.client = client; }

        /**
         * Main handler loop executed in a dedicated thread. It reads the
         * request line and headers, enforces a simple header size limit and
         * dispatches to either handleConnect (for CONNECT) or handleHttp for
         * other methods.
         */
        @Override
        public void run() {
            try (InputStream cin = client.getInputStream();
                 OutputStream cout = client.getOutputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(cin))) {

                // Lire première ligne
                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }
                StringBuilder rawHeaders = new StringBuilder();
                String line;
                int maxHeader = 32 * 1024; // simple securinty (DDOS)
                List<String> headerLines = new ArrayList<>();
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    rawHeaders.append(line).append("\r\n");
                    headerLines.add(line);
                    if (rawHeaders.length() > maxHeader) {
                        sendError(cout, 431, "Request Header Fields Too Large");
                        return;
                    }
                }
                // Fin des headers
                String method = requestLine.split(" ")[0];
                if ("CONNECT".equalsIgnoreCase(method)) {
                    // Reject HTTPS requests by sending an error response
                    sendError(cout, 403, "HTTPS requests are not allowed");
                    return;
                } else {
                    handleHttp(requestLine, headerLines, reader, cin, cout);
                }
            } catch (Exception e) {
                // Log erreur
                System.err.println("Erreur handler: " + e.getMessage());
            } finally {
                try { client.close(); } catch (IOException ignored) {}
            }
        }

        /**
         * Handle an HTTP CONNECT request to establish a raw TCP tunnel.
         *
         * The expected request line format is: CONNECT host:port HTTP/1.1
         *
         * This method parses the target host and port, establishes a socket
         * connection to the remote host, replies with 200 Connection
         * Established to the client and then copies bytes bidirectionally
         * between client and remote (without inspecting or modifying them).
         *
         * @param requestLine the full request line from the client
         * @param headers list of header lines (unused for tunnelling but kept)
         * @param cin InputStream of the client socket (used to read further data)
         * @param cout OutputStream to the client socket
         * @param reader BufferedReader for convenience (not used after parsing)
         * @throws IOException on I/O errors when connecting to remote or writing
         */
        @SuppressWarnings("unused")
        private void handleConnect(String requestLine, List<String> headers, InputStream cin, OutputStream cout, BufferedReader reader) throws IOException {
            // Format attendu: CONNECT host:port HTTP/1.1
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendError(cout, 400, "Bad CONNECT");
                return;
            }
            String hostPort = parts[1];
            String host = hostPort;
            int port = 443;
            int idx = hostPort.indexOf(':');
            if (idx != -1) {
                host = hostPort.substring(0, idx);
                port = Integer.parseInt(hostPort.substring(idx + 1));
            }
            logRequest("CONNECT", host + ":" + port);
            try (Socket remote = new Socket(host, port)) {
                remote.setSoTimeout(60_000);
                cout.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: SimpleJavaProxy/1.0\r\n\r\n".getBytes());
                cout.flush();
                // Tunnel binaire bidirectionnel
                Thread t1 = new Thread(() -> streamCopy(cin, getOutput(remote)), "C2R");
                Thread t2 = new Thread(() -> streamCopy(getInput(remote), cout), "R2C");
                t1.start();
                t2.start();
                try { t1.join(); } catch (InterruptedException ignored) {}
                try { t2.join(); } catch (InterruptedException ignored) {}
            } catch (IOException e) {
                sendError(cout, 502, "Bad Gateway");
            }
        }

        /**
         * Handle a standard HTTP request (GET, POST, etc.) by forwarding it to
         * the target server and streaming the response back to the client.
         *
         * This method handles both absolute-form request-targets (e.g.
         * GET http://host/path HTTP/1.1) and origin-form (/path). It extracts
         * the Host header when needed and rewrites the request-line to use a
         * relative path before sending to the remote server. For simplicity
         * this implementation forces Connection: close and does not handle
         * complex request bodies (e.g. chunked or large POST bodies).
         *
         * @param requestLine the original request line from the client
         * @param headers the list of header lines read from the client
         * @param reader reader attached to the client input stream
         * @param cin client input stream
         * @param cout client output stream
         * @throws IOException on I/O errors
         */
        @SuppressWarnings("unused")
        private void handleHttp(String requestLine, List<String> headers, BufferedReader reader, InputStream cin, OutputStream cout) throws IOException {
            // Example: {@code GET http://example.com/path HTTP/1.1} or {@code GET /path HTTP/1.1}
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) { sendError(cout, 400, "Bad Request"); return; }
            String method = parts[0];
            String urlPart = parts[1];
            String httpVersion = parts[2];
            String host = null;
            int port = 80;

            // Si URL absolue
            if (urlPart.startsWith("http://")) {
                int idx = urlPart.indexOf('/', 7);
                String authority = idx == -1 ? urlPart.substring(7) : urlPart.substring(7, idx);
                int c = authority.indexOf(':');
                if (c != -1) {
                    host = authority.substring(0, c);
                    port = Integer.parseInt(authority.substring(c + 1));
                } else host = authority;
                urlPart = idx == -1 ? "/" : urlPart.substring(idx);
            }

            for (String h : headers) {
                if (h.toLowerCase().startsWith("host:")) {
                    if (host == null) {
                        host = h.substring(5).trim();
                        int c = host.indexOf(':');
                        if (c != -1) {
                            port = Integer.parseInt(host.substring(c + 1));
                            host = host.substring(0, c);
                        }
                    }
                }
            }
            if (host == null) { sendError(cout, 400, "Host Required"); return; }
            logRequest("HTTP", method + " " + host + ":" + port + urlPart);

            try (Socket remote = new Socket(host, port)) {
                remote.setSoTimeout(30_000);
                OutputStream rout = remote.getOutputStream();
                InputStream rin = remote.getInputStream();

                // Réécrire la requête avec chemin relatif
                String newRequestLine = method + " " + urlPart + " " + httpVersion + "\r\n";
                rout.write(newRequestLine.getBytes());
                // Filtrer et réécrire certains headers
                for (String h : headers) {
                    String lower = h.toLowerCase();
                    if (lower.startsWith("proxy-connection:")) continue; // supprimer
                    if (lower.startsWith("connection:")) {
                        // forcer close pour simplicité
                        rout.write("Connection: close\r\n".getBytes());
                    } else {
                        rout.write((h + "\r\n").getBytes());
                    }
                }
                rout.write("\r\n".getBytes());
                rout.flush();

                // Pas de body (on ne gère pas ici POST avec body complexe) => amélioration possible
                // Copier la réponse brute vers le client
                streamCopy(rin, cout);
            } catch (IOException e) {
                sendError(cout, 504, "Gateway Timeout");
            }
        }

        /**
         * Log an incoming request to stdout with a timestamp.
         *
         * @param type request type label (e.g. "CONNECT" or "HTTP")
         * @param detail additional details (host:port, path, ...)
         */
        private static void logRequest(String type, String detail) {
            System.out.println("[" + new Date() + "] " + type + " " + detail);
        }

        /**
         * Send a minimal HTTP error response back to the client.
         *
         * @param out client output stream
         * @param code HTTP status code
         * @param msg status message
         * @throws IOException on I/O error while writing
         */
        private static void sendError(OutputStream out, int code, String msg) throws IOException {
            String body = "<html><body><h1>" + code + " " + msg + "</h1></body></html>";
            String resp = "HTTP/1.1 " + code + " " + msg + "\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: " + body.getBytes().length + "\r\n" +
                    "Connection: close\r\n\r\n" + body;
            out.write(resp.getBytes());
            out.flush();
        }

        /**
         * Copy bytes from an InputStream to an OutputStream until EOF. This is
         * used for both streaming HTTP responses and for tunnelling raw
         * connections. The method swallows IOExceptions to allow cleaner
         * thread termination when sockets are closed.
         *
         * @param in source input stream
         * @param out destination output stream
         */
        private static void streamCopy(InputStream in, OutputStream out) {
            byte[] buf = new byte[8192];
            int r;
            try {
                // Read the initial bytes to determine content type
                r = in.read(buf);
                if (r == -1) {
                    System.err.println("No data received from the server.");
                    return; // EOF
                }

                // Check for text-based content types
                String contentType = null;
                if (r > 0) {
                    String responseLine = new String(buf, 0, r, StandardCharsets.UTF_8);
                    System.out.println("Initial response line: " + responseLine);
                    if (responseLine.startsWith("HTTP/")) {
                        // Extract Content-Type from headers
                        int headerEndIndex = responseLine.indexOf("\r\n\r\n");
                        if (headerEndIndex != -1) {
                            String headers = responseLine.substring(0, headerEndIndex);
                            System.out.println("Headers: " + headers);
                            for (String header : headers.split("\r\n")) {
                                if (header.toLowerCase().startsWith("content-type:")) {
                                    contentType = header.substring(13).trim();
                                    break;
                                }
                            }
                        }
                    }
                }

                // Debugging logs
                System.out.println("Content-Type detected: " + contentType);

                // If content type is text/html, modify the response
                if (contentType != null && contentType.contains("text/html")) {
                    ByteArrayOutputStream tempBuffer = new ByteArrayOutputStream();
                    tempBuffer.write(buf, 0, r); // Write the initial bytes
                    while ((r = in.read(buf)) != -1) {
                        tempBuffer.write(buf, 0, r);
                    }

                    // Convert the response to a string for modification
                    String response = tempBuffer.toString(StandardCharsets.UTF_8);

                    // Separate headers and body
                    int headerEndIndex = response.indexOf("\r\n\r\n");
                    if (headerEndIndex == -1) {
                        // If no headers are found, treat the entire response as the body
                        out.write(response.getBytes(StandardCharsets.UTF_8));
                        return;
                    }

                    String headers = response.substring(0, headerEndIndex);
                    String body = response.substring(headerEndIndex + 4);

                    // --- Protection: do not modify <img ...src="...Stockholm..." ...> ---
                    List<String> protectedImgSnippets = new ArrayList<>();
                    Pattern imgPattern = Pattern.compile("(<img[^>]*src=\"[^\"]*Stockholm[^\"]*\"[^>]*>)", Pattern.CASE_INSENSITIVE);
                    Matcher imgMatcher = imgPattern.matcher(body);
                    StringBuffer sb = new StringBuffer();
                    while (imgMatcher.find()) {
                        protectedImgSnippets.add(imgMatcher.group(1));
                        imgMatcher.appendReplacement(sb, "__PROTECTED_IMG_" + (protectedImgSnippets.size() - 1) + "__");
                    }
                    imgMatcher.appendTail(sb);
                    body = sb.toString();

                    // Modify the body (except protected <img> tags)
                    body = body.replace("Stockholm", "Linköping");
                    body = body.replace("Smiley", "Trolly");
                    body = body.replace("https://zebroid.ida.liu.se/fakenews/smiley.jpg", "https://zebroid.ida.liu.se/fakenews/trolly.jpg");
                    body = body.replace("./smiley.jpg", "./trolly.jpg");

                    // Restore protected <img> tags
                    for (int i = 0; i < protectedImgSnippets.size(); i++) {
                        body = body.replace("__PROTECTED_IMG_" + i + "__", protectedImgSnippets.get(i));
                    }

                    // Update Content-Length header
                    int newContentLength = body.getBytes(StandardCharsets.UTF_8).length;
                    if (headers.toLowerCase().contains("content-length:")) {
                        headers = headers.replaceAll("(?i)(Content-Length:[ \\t\\n\\x0B\\f\\r]*)\\d+", "Content-Length: " + newContentLength);
                    } else {
                        headers += "\r\nContent-Length: " + newContentLength;
                    }

                    // Write the updated headers and body back to the output stream
                    out.write((headers + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                } else {
                    // For non-text content, copy the data as-is without modification
                    out.write(buf, 0, r); // Write the initial bytes
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                    }
                }

                System.out.println("Data successfully forwarded to the client.");
            } catch (IOException e) {
                System.err.println("Error during stream copy: " + e.getMessage());
            }
        }

        /**
         * Helper to obtain the InputStream of a socket and wrap checked
         * exceptions into a runtime exception for convenience in lambda
         * expressions.
         *
         * @param s socket
         * @return input stream
         */
        private static InputStream getInput(Socket s) {
            try { return s.getInputStream(); } catch (IOException e) { throw new RuntimeException(e); }
        }

        /**
         * Helper to obtain the OutputStream of a socket and wrap checked
         * exceptions into a runtime exception for convenience in lambda
         * expressions.
         *
         * @param s socket
         * @return output stream
         */
        private static OutputStream getOutput(Socket s) {
            try { return s.getOutputStream(); } catch (IOException e) { throw new RuntimeException(e); }
        }
    }
}
