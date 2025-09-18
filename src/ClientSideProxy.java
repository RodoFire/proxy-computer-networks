import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
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
            if (line.startsWith("CONNECT")){
                System.out.println("HTTPS request, we refuse the connection");
                return new ArrayList<>();
            }
            headers.add(line);
            System.out.println(line);
        }
        return headers;
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

            sendResponseToClient(headers);
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
