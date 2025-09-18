public class Main {
    public static void main(String[] args) throws Exception {
        int port = 8080; // Port proxy
        if (args.length > 0) {
            try { port   = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        System.out.println("Démarrage proxy sur port " + port);
        new ProxyServer(port).start();
    }
}