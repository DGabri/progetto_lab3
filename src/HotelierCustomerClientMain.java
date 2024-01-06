import java.nio.charset.StandardCharsets;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

public class HotelierCustomerClientMain {
    // config variables and asset path
    private static final String CLIENT_CONFIG = "./assets/client.properties";
    private static String SERVER_ADDRESS;
    private static String MULTICAST_ADDR;
    private static int PORT;

    // selector variables
    private MulticastSocket multicastSocket;
    private SocketChannel socketChannel;
    private InetAddress multicastGroup;
    private Selector selector;
    Thread notificationsThread;

    // login state client side
    private boolean loggedIn = false;
    private String username = "";

    public HotelierCustomerClientMain() {
        try {

            // load config
            Properties prop = loadConfig(CLIENT_CONFIG);
            PORT = Integer.parseInt(prop.getProperty("port"));
            SERVER_ADDRESS = prop.getProperty("serverAddress");
            MULTICAST_ADDR = prop.getProperty("multicastAddress");

            System.out.println("PORT: " + PORT + " ADDR: " + SERVER_ADDRESS);
            // Open selector and socket channel
            selector = Selector.open();

            // connect
            socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);
            socketChannel.connect(new InetSocketAddress(SERVER_ADDRESS, PORT));
            socketChannel.register(selector, SelectionKey.OP_CONNECT);

            // wait for socket to be completely connected
            while (!socketChannel.finishConnect()) {
            }

            // multicast socket init
            multicastSocket = new MulticastSocket(PORT + 1);
            multicastSocket.setReuseAddress(true);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        HotelierCustomerClientMain client = new HotelierCustomerClientMain();

        try (Scanner scanner = new Scanner(System.in)) {
            String input;

            while (true) {
                // Display menu
                client.printOptions();

                // Get user input
                input = scanner.nextLine();

                switch (input) {
                    /* REGISTRAZIONE */
                    case "1":
                        System.out.print("Inserisci username: ");
                        String usernameRegister = scanner.nextLine();
                        System.out.print("Inserisci password: ");
                        String passwordRegister = scanner.nextLine();

                        // error if password is empty
                        if (passwordRegister.length() == 0) {
                            System.out.println("Inserirsci una password non vuota");
                            break;
                        }

                        // get response code
                        String resCode = client.register(usernameRegister, passwordRegister);
                        if (resCode.equals("-1")) {
                            System.out.println("Utente gia' registrato");
                        }

                        break;

                    /* LOGIN */
                    case "2":
                        // get username and password and send them to server
                        System.out.print("Inserisci username di login: ");
                        String usernameLogin = scanner.nextLine();
                        System.out.print("Inserisci password di login: ");
                        String passwordLogin = scanner.nextLine();

                        // login
                        client.login(usernameLogin, passwordLogin);
                        break;

                    /* LOGOUT */
                    case "3":
                        // esegui logout se l'username e' presente
                        if (client.username.length() != 0) {
                            client.logout(client.username);
                        } else {
                            System.out.println("Hai gia' effettuato il logout");
                        }
                        break;

                    /* CERCA HOTEL */
                    case "4":
                        System.out.print("Inserisci nome hotel: ");
                        String nomeHotel = scanner.nextLine();
                        System.out.print("Inserisci citta' hotel: ");
                        String citta = scanner.nextLine();

                        client.searchHotel(nomeHotel, citta);
                        break;

                    /* CERCA TUTTI GLI HOTELS IN UNA DETERMINATA CITTA' */
                    case "5":
                        System.out.print("Inserisci citta' per cercare hotel: ");
                        String cittaTuttiHotel = scanner.nextLine();

                        System.out.print("Citta': " + cittaTuttiHotel);
                        client.searchAllHotels(cittaTuttiHotel);

                        break;

                    /* INSERISCI RECENSIONE */
                    case "6":
                        // init empty review
                        int[] reviewPoints = { 0, 0, 0, 0 };

                        System.out.print("Inserisci il nome dell'hotel da recensire: ");
                        String reviewedHotelName = scanner.nextLine();
                        System.out.print("Inserisci la citta' dell'hotel da recensire: ");
                        String reviewedHotelCity = scanner.nextLine();

                        // reviews values
                        System.out.println("Inserisci i punteggi per la recensione, valori ammessi 0-5 inclusi");
                        System.out.print("Inserisci un punteggio per la posizione: ");
                        int positionScore = Integer.parseInt(scanner.nextLine());
                        System.out.print("Inserisci un punteggio per la pulizia: ");
                        int cleaningScore = Integer.parseInt(scanner.nextLine());
                        System.out.print("Inserisci un punteggio per il servizio: ");
                        int serviceScore = Integer.parseInt(scanner.nextLine());
                        System.out.print("Inserisci un punteggio per la qualita': ");
                        int qualityScore = Integer.parseInt(scanner.nextLine());

                        // check that points are between [0,5]
                        // if false a score is wrong, break and try again
                        if (!(client.checkScoreRange(positionScore) && client.checkScoreRange(cleaningScore)
                                && client.checkScoreRange(serviceScore) && client.checkScoreRange(qualityScore))) {
                            break;
                        }

                        // populate scores array
                        reviewPoints[0] = positionScore;
                        reviewPoints[1] = cleaningScore;
                        reviewPoints[2] = serviceScore;
                        reviewPoints[3] = qualityScore;

                        // calculate global score as the mean of the single scores
                        int globalScore = (reviewPoints[0] + reviewPoints[1] + reviewPoints[2] + reviewPoints[3]) / 4;

                        // send review to server
                        client.insertReview(reviewedHotelName, reviewedHotelCity, globalScore, reviewPoints);
                        break;

                    /* MOSTRA BADGE UTENTE */
                    case "7":
                        client.showMyBadges();
                        break;

                    /* ESCI */
                    case "8":
                        // close selector and socket channel
                        writeRead(client.socketChannel, "8_exit");
                        client.selector.close();
                        client.socketChannel.close();
                        // close notifications multicast group if it was started
                        if (client.loggedIn == true) {
                            client.closeNotificationsGroup();
                        }
                        System.exit(0);

                    default:
                        System.out.println("Valore non corretto, inserisci un valore tra quelli elencati");
                        break;
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // function to load configuration file
    public static Properties loadConfig(String fname) {
        try (FileInputStream fileInputStream = new FileInputStream(fname)) {
            Properties properties = new Properties();
            properties.load(fileInputStream);

            return properties;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    // function to print options available
    private void printOptions() {
        // display these lines only if not logged in
        if (!this.loggedIn) {
            System.out.println("-----------------------");
            System.out.println("Scegli azione:");
            System.out.println("1 Sign Up");
            System.out.println("2 Login");
            System.out.println("4 Cerca Hotel");
            System.out.println("5 Cerca alberghi in una citta'");
            System.out.println("8 Termina");
            System.out.println("-----------------------");
            System.out.print("Inserisci un valore compreso tra 1 ed 8: ");
        }
        if (this.loggedIn) {
            System.out.println("-----------------------");
            System.out.println("Scegli azione:");
            System.out.println("3 Logout");
            System.out.println("4 Cerca Hotel");
            System.out.println("5 Cerca alberghi in una citta'");
            System.out.println("6 Inserisci Recensione");
            System.out.println("7 Mostra Livello Utente");
            System.out.println("8 Termina");
            System.out.println("-----------------------");
            System.out.print("Inserisci un valore compreso tra 1 ed 8: ");
        }

    }

    // helper function to check if score is valide
    public boolean checkScoreRange(int value) {
        return ((value >= 0) && (value <= 5));
    }

    // function to register a new user
    public String register(String username, String password) {

        if ((username.length() != 0) && (password.length() != 0)) {
            // prepare string to send
            String msg = "1_" + username + "_" + password;

            // send string and receive response
            String res = writeRead(socketChannel, msg);
            System.out.println("-----> RESPONSE: " + res);
            return res;
        } else {
            System.out.println("Credenziali invalide, riprovare, lunghezza minima > 0");
        }

        return "";
    }

    // function to login
    public void login(String username, String password) {
        // send server(username, password)
        if (!this.loggedIn) {

            if ((username.length() != 0) && (password.length() != 0)) {

                String msg = "2_" + username + "_" + password;

                String retCode = writeRead(socketChannel, msg);

                if (retCode.equals("1")) {
                    // save that login was successful
                    this.loggedIn = true;
                    this.username = username;

                    System.out.println("-----> RESPONSE: Login effettuato");

                    // start thread for multicast and listen to notifications
                    startNotificationsThread();
                    subscribeToMulticastGroup();

                } else if (retCode.equals("-1")) {
                    System.out.println("-----> RESPONSE: Login ERROR, password errata");
                } else {
                    System.out.println("-----> RESPONSE: Login ERROR, utente non registrato");
                }
            }
            else {
                System.out.println(" **** INSERT VALID USERNAME AND PASSWORD WITH LENGTH > 0 ****");
            }
        }
    }

    // logout
    public void logout(String username) {

        // prepare string to send
        String msg = "3_" + this.username;

        String retCode = writeRead(socketChannel, msg);

        if (retCode.equals("1")) {
            this.loggedIn = false;
            this.username = "";
            System.out.println("-----> RESPONSE: Logout effettuato");

        } else {
            System.out.println("-----> RESPONSE: Logout ERROR");
        }
    }

    // function to search 1 hotel
    public String searchHotel(String nomeHotel, String citta) {

        // prepare string to send
        String msg = "4_" + nomeHotel + "_" + citta;
        if ((nomeHotel.length() != 0) || (citta.length() != 0)) {
            String responseHotel = writeRead(socketChannel, msg);
            System.out.println("-----> RESPONSE: HOTEL -> " + "\n" + responseHotel);
            return responseHotel;
        }

        return "4_empty";

    }

    // function to ask server for all the hotels in a given city
    public void searchAllHotels(String citta) {

        // prepare string to send
        String msg = "5_" + citta;

        if (citta.length() != 0) {
            String responseHotels = writeRead(socketChannel, msg);
            System.out.println("-----> RESPONSE: HOTELS per: " + citta + " -> " + "\n" + responseHotels);
        } else {
            System.out.println("Insert a city");
        }
    }

    // function to extract input data and send it to the server
    public void insertReview(String nomeHotel, String nomeCitta, int globalScore, int[] singleScores) {
        if (this.loggedIn) {
            // prepare string to send

            String msg = "6" + "_" + this.username + "_" + nomeHotel + "_" + nomeCitta + "_"
                    + String.valueOf(globalScore) + "_"
                    + String.valueOf(singleScores[0]) + "_" + String.valueOf(singleScores[1]) + "_"
                    + String.valueOf(singleScores[2]) + "_" + String.valueOf(singleScores[3]);

            // send data to server
            String retCode = writeRead(socketChannel, msg);
            System.out.println("-----> RESPONSE:" + retCode);
        }
    }

    // function to show user badge
    public void showMyBadges() {
        if (this.loggedIn) {
            // prepare string to send
            String msg = "7_" + this.username;

            String badgeName = writeRead(socketChannel, msg);
            System.out.println("-----> RESPONSE: Il tuo badge attuale e' " + badgeName);
        }
    }

    private static String writeRead(SocketChannel socketChannel, String msg) {
        try {
            // convert msg to bytes
            byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);

            // create buffer with lenghth 1 integeer + msg length
            ByteBuffer writeBuffer = ByteBuffer.allocate(Integer.BYTES + msgBytes.length);
            
            // put the length of the message so server knows how much needs to read
            writeBuffer.putInt(msgBytes.length);
            writeBuffer.put(msgBytes);
            writeBuffer.flip();

            // write effective message until everything is written
            while (writeBuffer.hasRemaining()) {
                socketChannel.write(writeBuffer);
            }

            // get server response length
            ByteBuffer lengthBuffer = ByteBuffer.allocate(Integer.BYTES);
            while (socketChannel.read(lengthBuffer) <= 0) {
            }


            lengthBuffer.flip();
            int responseMsgLength = lengthBuffer.getInt();

            // read response msg
            ByteBuffer responseMsgBuffer = ByteBuffer.allocate(responseMsgLength);
            
            while (socketChannel.read(responseMsgBuffer) <= 0) {
                // wait for data
            }
            responseMsgBuffer.flip();

            // convert message bytes to string
            byte[] responseStringBytes = new byte[responseMsgBuffer.remaining()];
            responseMsgBuffer.get(responseStringBytes);

            return new String(responseStringBytes, StandardCharsets.UTF_8);

        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    /* UDP NOTIFICATION RECEIVER */
    private void subscribeToMulticastGroup() {
        try {
            multicastGroup = InetAddress.getByName(MULTICAST_ADDR);
            multicastSocket.joinGroup(multicastGroup);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // function to start the notification handler thread
    public void startNotificationsThread() {
        // start udp thread

        this.notificationsThread = new Thread(() -> this.startNotificationReceiver());
        notificationsThread.start();
    }

    // function called by thread to print received message
    public void startNotificationReceiver() {
        System.out.println("STARTED NOTIFICATION RECEIVER THREAD");
        try {

            byte[] buffer = new byte[1024];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                this.multicastSocket.receive(packet);

                String received = new String(packet.getData(), 0, packet.getLength());
                System.out.println("");
                System.out.println(" -----> MULTICAST UPDATE: " + received);
                System.out.println("");
                printOptions();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // function to close the multicast group
    public void closeNotificationsGroup() {
        this.multicastSocket.close();
    }
}
