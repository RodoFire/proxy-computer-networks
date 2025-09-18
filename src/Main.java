import java.util.Scanner;

void main() {
    ServerSideProxy serverSideProxy = new ServerSideProxy();
    serverSideProxy.start();

    while (true) {
        Scanner scanner = new Scanner(System.in);
        String s = scanner.nextLine();
        System.out.println(s);
        if (s.equals("q")) {
            serverSideProxy.interrupt();
            break;
        }
    }
}
