import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ClientSideProxy extends Thread {
    Socket clientSocket;

    public ClientSideProxy(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try {
            handleClientConnection(clientSocket);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void handleClientConnection(Socket clientSocket) throws IOException {
        InputStream in = clientSocket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));

        while (!clientSocket.isClosed()) {
            System.out.println("Reading request from client: ");
            List<String> headers = getHeaders(reader);
            System.out.println("---");

            if (headers.isEmpty()) {
                break;
            }
            headers.forEach(System.out::println);

            Socket serverSocket = getSocketFromRequest(headers);
            if (serverSocket == null) {
                break;
            }

            sendRequestToServer(serverSocket, headers);

            listenFromServer(serverSocket);
            serverSocket.close();
        }
    }

    private static List<String> getHeaders(BufferedReader reader) throws IOException {
        List<String> headers = new ArrayList<>();
        String line;

        while ((line = reader.readLine()) != null && !line.isEmpty()) {

            //HTTPS request, we refuse the connection
            if (line.startsWith("CONNECT")) {
                System.out.println("HTTPS request, we refuse the connection");
                return new ArrayList<>();
            }
            headers.add(line);
            System.out.println(line);
        }
        return headers;
    }

    int contentLength(List<String> headers) {
        for (String header : headers) {
            Integer length = ContentModifier.getContentLength(header);
            if (length != null) return length;
        }
        return 0;
    }

    boolean isChunked(List<String> headers) {
        for (String header : headers) {
            if (header.startsWith("Transfer-Encoding") && header.contains("chunked")) {
                return true;
            }
        }
        return false;
    }

    byte[] getBody(InputStream in, int contentLength) throws IOException {
        if (contentLength > 0) {
            System.out.println("contentLength: " + contentLength);
            byte[] body = new byte[contentLength];
            int totalRead = 0;

            while (totalRead < contentLength) {
                int bytesRead = in.read(body, totalRead, contentLength - totalRead);
                if (bytesRead == -1) {
                    System.out.println("Stream ended prematurely at " + totalRead + " bytes");
                    break;
                }
                totalRead += bytesRead;
            }

            if (totalRead != contentLength) {
                System.out.println("different body length: got " + totalRead + " instead of " + contentLength);
                return Arrays.copyOf(body, totalRead);
            }

            return body;
        }
        return null;
    }

    private void sendRequestToServer(Socket serverRequestSocket, List<String> requestLines) throws IOException {
        System.out.println("Sending request to server");
        OutputStream out = serverRequestSocket.getOutputStream();
        sendRequest(requestLines, out);
    }

    private void sendResponseToClient(List<String> requestLines) throws IOException {
        System.out.println("Sending response to client");
        OutputStream out = clientSocket.getOutputStream();
        sendRequest(requestLines, out);
    }

    private static void sendRequest(List<String> requestLines, OutputStream out) throws IOException {
        for (String line : requestLines) {
            out.write((line + "\r\n").getBytes());
        }
        out.write("\r\n".getBytes());
        out.flush();
    }

    private void listenFromServer(Socket serverRequestSocket) throws IOException {
        InputStream in = serverRequestSocket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));

        while (!serverRequestSocket.isClosed()) {
            System.out.println("Reading request from server: ");
            List<String> headers = getHeaders(reader);
            System.out.println("---");

            if (headers.isEmpty()) {
                break;
            }

            int alteredContentLength = ContentModifier.handleText(headers);
            ContentModifier.modifyContentLength(headers, alteredContentLength);
            sendResponseToClient(headers);
/*
            OutputStream clientOut = clientSocket.getOutputStream();
            String line;
            while ((line = reader.readLine()) != null) {
                clientOut.write((line + "\r\n").getBytes());
            }
            clientOut.write("\r\n".getBytes());
            clientOut.flush();

            int contentLength = contentLength(headers);
            byte[] body = getBody(in, contentLength);
            if (body != null) {
                System.out.println("body not null: " + body.length);
                clientOut.write(body);
            }
            clientOut.flush();*/
        }
    }

    /**
     * extract the url of the request, and creates a socket to connect to it.
     */
    private Socket getSocketFromRequest(List<String> request) throws IOException {
        for (String s : request) {
            if (s.startsWith("Host:")) {
                String url = s.split(" ")[1];
                System.out.println("Connecting to " + url + "");
                System.out.println("---");
                return new Socket(url, 80);
            }
        }
        return null;
    }
}
