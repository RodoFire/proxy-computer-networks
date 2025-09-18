// Demonstrating Client-side Programming
import java.io.*;
import java.net.*;

public class Client {

    // Initialize socket and input/output streams
    private Socket s = null;
    private DataInputStream in = null;
    private DataOutputStream out = null;

    // Constructor to put IP address and port
    public Client(String addr, int port)
    {
        // Establish a connection
        try {
            s = new Socket(addr, port);
            System.out.println("Connected");

            // Takes input from terminal
            in = new DataInputStream(System.in);

            // Sends output to the socket
            out = new DataOutputStream(s.getOutputStream());
        }
        catch (UnknownHostException u) {
            u.printStackTrace();
            return;
        }
        catch (IOException i) {
            i.printStackTrace();
            return;
        }

        // String to read message from input
        String m = "";

        // Keep reading until "Over" is input
        while (!m.equals("Over")) {
            try {
                m = in.readLine();
                out.writeUTF(m);
            }
            catch (IOException i) {
                i.printStackTrace();
            }
        }

        // Close the connection
        try {
            in.close();
            out.close();
            s.close();
        }
        catch (IOException i) {
            i.printStackTrace();
        }
    }

    public static void main(String[] args) {
        Client c = new Client("127.0.0.1", 5000);
    }
}