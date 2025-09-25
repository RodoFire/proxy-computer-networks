import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

public class ServerSideProxy extends Thread {
    private static boolean stop = false;
    private ServerSocket serverSocket;
    private List<ClientSideProxy> clientSideProxies = new ArrayList<>();


    @Override
    public void run() {
        try {
            this.serverSocket = new ServerSocket(8000);
            listen();
        } catch (SocketException e) {
            if (ServerSideProxy.stop) {
                System.out.println("Server stopped.");
            } else {
                e.printStackTrace();
            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void listen() throws IOException {
        while (!ServerSideProxy.stop) {
            System.out.println("Waiting for client connection...");
            Socket clientSocket = this.serverSocket.accept();
            System.out.println("accepted client");
            ClientSideProxy clientSideProxy = new ClientSideProxy(clientSocket);
            clientSideProxies.add(clientSideProxy);
            clientSideProxy.start();
        }
    }


    @Override
    public void interrupt() {
        ServerSideProxy.stop = true;
        try {
            serverSocket.close();
            clientSideProxies.forEach(ClientSideProxy::interrupt);
        } catch (IOException ignored) {
        }
        super.interrupt();
    }
}
