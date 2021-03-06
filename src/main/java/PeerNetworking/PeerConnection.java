package PeerNetworking;

import Controller.ChatInterface;
import Cryptography.AssymEncypt;
import QueryObjects.ChatMessage;
import QueryObjects.ChatRequest;
import QueryObjects.FriendData;
import QueryObjects.UserData;
import Util.JSONhelper;
import com.google.gson.Gson;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.swing.plaf.synth.SynthTextAreaUI;
import java.io.*;
import java.net.*;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.TimeoutException;

public class PeerConnection {
    private final String token =  "fXtas7yB2HcIVoCyyQ78";
    private static Gson gson = new Gson();
    private static ConnectionManager manager = null;
    private int localPort;
    private String localTestIP;
    private int localTestPort;
    private int peerPort;
    private UserData user;
    private FriendData friend = null;
    private ChatRequest request = null;
    private boolean running = true;
    private Socket connectionClient = null;
    private ServerSocket connectionListener = null;
    private Thread incomingThread = null;
    private Thread testServer;
    private String peerIP;
    private ChatInterface parentWindow = null;
    private AssymEncypt encypt;

    private boolean isServer;

    public synchronized UserData getUser(){return user;}

    private synchronized boolean getRunning(){ return running;}

    private synchronized void setRunning(boolean set){
        running = set;
    }

    public void setParentWindow(ChatInterface window) { parentWindow = window;}

    public void setFriend(FriendData friend){this.friend = friend;}

    public PeerConnection(UserData usr, ChatRequest req) throws IOException {
        if (manager == null) manager = ConnectionManager.getConnectionManager(usr);
        this.user = usr;
        this.request = req;
        this.isServer = false;

        if (request.targetUser.equals(user.username)){
            this.peerIP = request.requestingIPaddress.equals(user.ipAddress) ? request.requestingLocalIPaddress : request.requestingIPaddress;
            this.peerPort = Integer.parseInt(request.requestingPort);

        }
        //We sent the request
        else{
            if(request.targetIP.equals(user.ipAddress)){
                this.peerIP = user.privateIPaddress;
                this.isServer = true;
            }
            else{
                this.peerIP = request.targetIP;
            }
            this.peerPort = Integer.parseInt(req.targetPort);

        }
        this.localPort = Integer.parseInt(user.peerServerPort);
        try {
            this.encypt = AssymEncypt.getAssymEncypt();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            System.exit(1);
        } catch (NoSuchPaddingException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public int connectNatPunch(int port){
        JSONhelper jsonHelper = new JSONhelper();
        localPort = port;
        int attemptCount = 0;
        do {
            try {
                if(isServer){
                    /*
                    *   creating server socket in case the user is server. This only occurs when
                    *   both users are behind the same nat. The requesting user is then turned
                    *   into a server that the other peer connects to.
                    * */

                    connectionListener = new ServerSocket();
                    connectionListener.setReuseAddress(true);
                    connectionListener.bind(new InetSocketAddress(localPort));
                    System.out.printf("making server on: %d", connectionListener.getLocalPort());
                    connectionClient = connectionListener.accept();
                }
                else{
                    connectionClient = new Socket();
                    connectionClient.setReuseAddress(true);
                    System.out.println("Connect Punch, binding port: " + localPort);
                    connectionClient.bind(new InetSocketAddress(localPort));
                    System.out.println("Attempting connection, ip:port " + peerIP + ":" + peerPort);
                    connectionClient.connect(new InetSocketAddress(peerIP, peerPort), 15 * 1000);
                }

                //TODO: share keys and verify tokens
                String initialMessage = "{\"key\": \""+ encypt.getPublicKeyString() + "\", " +
                        "\"token\" : \"" + token +"\"}";
                sendMessageNoCrypt(initialMessage);

                //get message
                String receivedMessage;
                //TODO: test if I still need this
                int receiveCount = 0;
                do {
                    receivedMessage = getMessage();
                }while (receivedMessage == null && receiveCount < 5);
                //System.out.println("Received: " + receivedMessage);
                jsonHelper.parseBody(receivedMessage);
                String friendPublicKey = jsonHelper.getValueFromKey("key");
                String friendAPI = jsonHelper.getValueFromKey("token");
                if (!friendAPI.equals(token)){
                    System.out.println("API tokens do not match");
                    connectionClient.close();
                    return -1;
                }
                //make public key
                encypt.setFriendPublicKey(friendPublicKey);
                //System.out.println(getMessage());
                System.out.println(receivedMessage);
                System.out.flush();
            } catch (SocketException s) {
                s.printStackTrace();
            } catch (SocketTimeoutException to){
                to.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
                return -1;
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                return 1;
            } catch (InvalidKeySpecException e) {
                e.printStackTrace();
                System.out.println("Could not parse encryption key");
                return 2;
            }
            attemptCount++;
        }while(!connectionClient.isConnected() && attemptCount < 10);
        return 0;
    }

    public int connectNatless(){
        connectionClient = new Socket();
        try {
            findSocket();
        }
        catch(SocketException e){
            e.printStackTrace();
            return -1;
        }
        try {
            connectionClient.connect(new InetSocketAddress(peerIP, peerPort));
            //send token
            //TODO: make better
            String json = "{ \"token\": \"fXtas7yB2HcIVoCyyQ78\"}";
            sendMessage(json);
        }
        catch(IOException e){
            e.printStackTrace();
            return -2;
        }
        return 0;
    }

    public void startReceiving(){
        System.out.println("Starting reception");
        incomingThread = new Thread(this::receiveMessages);
        incomingThread.setDaemon(true);
        incomingThread.start();
    }

    private void receiveMessages(){
        //Trace:
       if(connectionClient.isConnected()) System.out.println("Is connected in receive messages");
       if(parentWindow == null) System.out.println("No parent window");
        //TODO: parse object in receive message
        try {
            //TODO: this is probably not a good hack - prevents trying to send
            //  messages to windows that don't yet exist
            Thread.sleep(1000);
            parentWindow.sendMessageToWindow("# Encrypted Chat Ready\n");
        }
        catch(InterruptedException e){
            e.printStackTrace();
        }
        try {
            BufferedReader input = getBuffer(connectionClient);
            while(getRunning()){
                //TODO: check for ending connection
                String msg = null;
                if(!connectionClient.isConnected()){
                    setRunning(false);
                    //TODO: call something in parentWindow to let user know friend disconnected
                    parentWindow.sendMessageToWindow("# Friend disconnected");
                }
                else {
                    msg = input.readLine();
                    if (parentWindow != null) {
                        if (msg != null) {
                            System.out.println("Received: " + msg);
                            ChatMessage message = gson.fromJson(msg, ChatMessage.class);
                            try {
                                String decrpytedMessage = encypt.decryptString(message.message);
                                System.out.println(decrpytedMessage);
                                parentWindow.sendMessageToWindow(parentWindow.userIsNotSource(decrpytedMessage));
                            } catch (InvalidKeyException e) {
                                e.printStackTrace();
                            } catch (BadPaddingException e) {
                                e.printStackTrace();
                            } catch (IllegalBlockSizeException e) {
                                e.printStackTrace();
                            }
                        }//if msg != null
                    } //parent not null
                }//client is connected
            } //while running
        }//try
        catch(IOException e){
            e.printStackTrace();
        }
        if (connectionClient != null){
            try{
                System.out.println("Closing connection");
                if (!connectionClient.isClosed()) {
                    connectionClient.close();
                }
            }
            catch(IOException e){
                e.printStackTrace();
            }
        }
    }

    public String getMessage() throws IOException {
        BufferedReader bufferedReader = getBuffer(connectionClient);
        return bufferedReader.readLine();
    }

    public int sendMessageNoCrypt(String msg){
        if (connectionClient == null) return 1;
        System.out.println(msg);
        try {
            PrintWriter out =
                    new PrintWriter(connectionClient.getOutputStream(), true);
            out.println(msg);
        }
        catch(IOException e){
            e.printStackTrace();
            return -1;
        }
        return 0;
    }

    public int sendMessage(String msg){
        //TODO: close connection on window close or program shutdown
        if (connectionClient == null) return 1;
        try {
            String encryptedMessage = encypt.encryptString(msg);
            ChatMessage message = new ChatMessage(token, encryptedMessage);
            String json = gson.toJson(message);
            System.out.println(json);
            try {
                PrintWriter out =
                        new PrintWriter(connectionClient.getOutputStream(), true);
                out.println(json);
            }
            catch(IOException e){
                e.printStackTrace();
                return -1;
            }
        } catch (InvalidKeyException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (BadPaddingException e) {
            e.printStackTrace();
        } catch (IllegalBlockSizeException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private BufferedReader getBuffer(Socket connectionClient) throws IOException {
        InputStream inputStream = connectionClient.getInputStream();
        return new BufferedReader((new InputStreamReader(inputStream)));
    }

    private void findSocket() throws SocketException {
        ServerSocket sock = null;
        if (localPort > 65535) throw new SocketException();
        while (true) {
            try {
                connectionClient.setReuseAddress(true);
                connectionClient.bind(new InetSocketAddress(localPort));
                break;
            }
            catch(IOException e){
                localPort++;
            }
        }
    }

    public synchronized void stopConnection(){
        setRunning(false);
        if (incomingThread != null && incomingThread.isAlive()){
                incomingThread.interrupt();
                //incomingThread.join();
        }

        if (connectionClient != null){
            try {
                if(!connectionClient.isClosed()) {
                    connectionClient.close();
                    if(isServer){
                        connectionListener.close();
                    }
                }
            }
            catch(IOException e){
                e.printStackTrace();
            }
        }
    }
}
