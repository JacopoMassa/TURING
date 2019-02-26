package Server;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import Utils.*;


public class MainServer
{
    //collezione contenente gli indirizzi multicast già assegnati
    static Set<String> usedAddresses = new HashSet<>();

    private static String generateMulticastAddress()
    {
        String address = "239";

        for (int i = 1; i <= 3; i++)
            address += "." + ( (int) (Math.random() * 256));

        return address;

    }

    public static void main(String[] args)
    {
        //collezione degli utenti registrati a TURING
        RegisteredUsers registeredUsers = new RegisteredUsers();

        //collezione dei file caricati dagli utenti su TURING
        ConcurrentHashMap<String, FileInfo> userFiles = new ConcurrentHashMap<>();



        /* Creazione del registry per poter fornire la funzione di "registrazione/deregistrazione" al servizio TURING" */
        try
        {
            IntRegistration stub = (IntRegistration) UnicastRemoteObject.exportObject(registeredUsers,0);

            LocateRegistry.createRegistry(Utils.REGISTRATION_PORT);
            Registry r=LocateRegistry.getRegistry(Utils.REGISTRATION_PORT);

            r.rebind("TURING-SERVER",stub);
        }
        catch (RemoteException e)
        { System.err.println("Communication error " + e.toString()); }

        /* -------------------------------------------- */

        /* Creo una funzione di cleanup, che al termine dell'esecuzione del server
           elimina la directory contenente tutti i file degli utenti.
         */

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    try { Utils.deleteDirectory(Utils.SERVER_FILES_PATH); }
                    catch(IOException ioe) {System.err.println("Can't delete " + Utils.SERVER_FILES_PATH); }
                }));

        /* Creazione del socket TCP per la notifica degli inviti */

        ServerSocketChannel inviteSSC;
        try
        {
            inviteSSC = ServerSocketChannel.open();
            ServerSocket inviteServerSocket = inviteSSC.socket();
            inviteServerSocket.bind(new InetSocketAddress(Utils.ADDRESS, Utils.INVITE_PORT));
        }
        catch(IOException ioe)
        {
            System.err.println("Error opening server socket for invites management " + ioe.toString() + ioe.getMessage());
            ioe.printStackTrace();
            return;
        }

        /* Creazione del socket TCP per tutte le altre richieste dei client */

        ServerSocketChannel clientSSC;
        Selector selector;
        try
        {
            clientSSC = ServerSocketChannel.open();
            ServerSocket clientServerSocket = clientSSC.socket();
            clientServerSocket.bind(new InetSocketAddress(Utils.ADDRESS, Utils.CLIENT_PORT));
            System.out.println("Server aperto su porta " + Utils.CLIENT_PORT);

            clientSSC.configureBlocking(false);
            selector = Selector.open();
            clientSSC.register(selector, SelectionKey.OP_ACCEPT);
        }
        catch (IOException ioe)
        {
            System.err.println("Error opening server socket for client reqeusts " + ioe.toString() + ioe.getMessage());
            ioe.printStackTrace();
            return;
        }

        while (true)
        {
            try { selector.select(); }
            catch (IOException ioe)
            {
                System.err.println("Error on select " + ioe.toString() + ioe.getMessage());
                ioe.printStackTrace();
                break;
            }

            //ottengo l'insieme delle chiavi pronte a operazioni di I/O
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

            while (iterator.hasNext())
            {
                SelectionKey key = iterator.next();

                // rimuove la chiave dal Selected Set, ma non dal registered Set
                try
                {
                    //sto accettando una nuova connessione
                    if(key.isAcceptable())
                    {
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        SocketChannel client = server.accept();
                        client.configureBlocking(false);
                        client.register(selector, SelectionKey.OP_READ);
                        System.out.println("Connessione dal client " + client.getLocalAddress() + " from " + client.getRemoteAddress());
                    }
                    else if(key.isReadable()) //sto per leggere da una socket
                    {
                        SocketChannel clientSocketChannel = (SocketChannel) key.channel();
                        Operation op_in;
                        opCode answerCode = opCode.OP_FAIL;

                        try
                        {
                            op_in = (Operation) Utils.recvObject(clientSocketChannel);
                            if(op_in == null)
                                throw new NullPointerException();
                        }
                        catch(ClassNotFoundException e)
                        {
                            System.err.println("Error on reading Utils.Operation " + e.toString() + " " + e.getMessage());
                            e.printStackTrace();
                            //in caso di errore sovrascrivo il codice con OP_FAIL
                            answerCode = opCode.OP_FAIL;
                            key.attach(answerCode);
                            key.interestOps(SelectionKey.OP_WRITE);
                            continue;
                        }
                        catch(NullPointerException e)
                        {
                            key.cancel();
                            clientSocketChannel.close();
                            continue;
                        }

                        String usr = op_in.getUsername();
                        String psw = op_in.getPassword();

                        switch (op_in.getCode())
                        {

                            case PENDING_INVITATIONS:
                            {
                                UserInfo userInfo = registeredUsers.getUser(usr);

                                /* mando inviti pendenti all'utente */
                                Utils.sendObject(clientSocketChannel,userInfo.getPendingInvitations());

                                /* pulisco la lista degli inviti pendenti */
                                userInfo.clearPendingInvites();

                                answerCode = opCode.OP_OK;
                                break;
                            }

                            case FILE_LIST:
                            {
                                String owner = op_in.getOwner();

                                boolean onlyMyFiles = usr.equals(owner);

                                /* mando all'utente la lista di file che può gestire,
                                   impostando l'esito di tale invio, che manderò successivamente
                                 */
                                try
                                {
                                    ArrayList<String> nameToSend = new ArrayList<>();
                                    for (String s: registeredUsers.getUser(usr).getFiles())
                                    {
                                        /* costruisco l'opportuno pattern che il client leggerà
                                           nomefile_owner_numsezioni
                                         */

                                        if(!onlyMyFiles || userFiles.get(s).getOwner().equals(owner))
                                            nameToSend.add(s + "_" + userFiles.get(s).getNsections());
                                    }
                                    Utils.sendObject(clientSocketChannel, nameToSend);
                                    answerCode = opCode.OP_OK;
                                }
                                catch(IOException ioe)
                                { answerCode = opCode.OP_FAIL; }
                                break;
                            }

                            case LOGIN:
                            {
                                UserInfo userInfo = registeredUsers.getUser(usr);

                                if(userInfo == null)
                                    answerCode = opCode.ERR_USER_UNKNOWN;
                                else if(!userInfo.getPassword().equals(psw))
                                    answerCode = opCode.ERR_WRONG_PASSWORD;
                                else if(userInfo.isOnline())
                                    answerCode = opCode.ERR_USER_ALREADY_LOGGED;
                                else if(registeredUsers.setStatus(usr,psw,1))
                                {
                                    /* salvo la socket che userà il client per ricevere gli inviti */
                                    SocketChannel inviteSocket = inviteSSC.accept();
                                    userInfo.setInviteSocketChannel(inviteSocket);

                                    System.out.println("Connessione per inviti " + inviteSocket.getLocalAddress() + " from " + inviteSocket.getRemoteAddress());

                                    /* creo la directory (solo se non esiste già)
                                       che conterrà tutti i file creati da questo utente */

                                    Files.createDirectories(Paths.get(Utils.SERVER_FILES_PATH + usr));

                                    answerCode = opCode.OP_OK;
                                }
                                else
                                    answerCode = opCode.OP_FAIL;
                                break;
                            }

                            case LOGOUT:
                            {
                                if(registeredUsers.setStatus(usr,psw,0))
                                {
                                    //mando la risposta al client (con OP_OK)
                                    //Utils.sendBytes(clientSocketChannel,answerCode.toString().getBytes());

                                    UserInfo userInfo = registeredUsers.getUser(usr);

                                    String editingFilename = userInfo.getEditingFilename();
                                    int editingSection = userInfo.getEditingSection();

                                    if(!editingFilename.equals(""))
                                    {
                                        //se l'utente stava editando una sezione, la sblocco
                                        FileInfo fileInfo = userFiles.get(editingFilename);
                                        fileInfo.unlockSection(editingSection - 1);

                                        //decremento il numero di collaboratori sul file
                                        fileInfo.decCounterEditors();
                                    }

                                    //chiudo le due socket del client
                                    key.cancel();
                                    clientSocketChannel.close();
                                    
                                    userInfo.getInviteSocketChannel().close();

                                    continue;
                                }
                                break;
                            }

                            case CREATE:
                            {
                                String filename = op_in.getFilename();
                                String collectionFileName = filename + "_" + usr;
                                int nsections = op_in.getSection();

                                //controllo che non esista un file con lo stesso nome, creato dallo stesso utente
                                if(userFiles.containsKey(collectionFileName))
                                {
                                    answerCode = opCode.ERR_FILE_ALREADY_EXISTS;
                                }
                                else //se non esiste, aggiungo il file alla collezione gestita dal server
                                {
                                    //creo la directory che conterrà le sezioni del file 'filename'
                                    Files.createDirectories(Paths.get(Utils.getPath(usr,filename,0,true)));


                                    boolean err = false;

                                    //creo le sezioni del file
                                    for (int i = 1; i <= nsections; i++)
                                    {
                                        File sec = new File(Utils.getPath(usr,filename,i,true));
                                        if(!sec.createNewFile())
                                        {
                                            err = true;
                                            break;
                                        }
                                    }

                                    if(!err)
                                    {
                                        //genero un indirizzo di multicast da assegnare al file che sto per creare
                                        String address;
                                        do
                                        { address = generateMulticastAddress(); }
                                        while(usedAddresses.contains(address));

                                        //creo la struttura dati contenente le info del file
                                        FileInfo fileInfo = new FileInfo(usr,nsections,address);

                                        //aggiungo l'indirizzo generato tra quelli in uso
                                        usedAddresses.add(address);

                                        fileInfo.setOwner(usr);
                                        userFiles.putIfAbsent(collectionFileName, fileInfo);


                                        //aggiungo il file alla lista di quelli gestibili dall'utente che l'ha creato
                                        registeredUsers.getUser(usr).addFile(collectionFileName);
                                        answerCode = opCode.OP_OK;
                                    }
                                    else
                                        answerCode = opCode.OP_FAIL;
                                }
                                break;
                            }

                            case SHOW_ALL:
                            case SHOW:
                            case EDIT:
                            {
                                String filename = op_in.getFilename();
                                String owner = op_in.getOwner();
                                String collectionFilename = filename + "_" + owner;
                                int section = op_in.getSection();

                                UserInfo userInfo = registeredUsers.getUser(usr);
                                FileInfo fileInfo = userFiles.get(collectionFilename);

                                if(userInfo.canEdit(collectionFilename)) //utente con permessi
                                {
                                    if(!op_in.getCode().equals(opCode.SHOW_ALL))
                                    {
                                        if(!fileInfo.isLocked(section-1)) //sezione non lockata
                                        {
                                            if(op_in.getCode().equals(opCode.EDIT))
                                            {
                                                //lock sulla sezione
                                                fileInfo.lockSection(section-1);

                                                //salvo qual è il file che l'utente sta modificando
                                                userInfo.setEditingFilename(collectionFilename);
                                                userInfo.setEditingSection(section);

                                                //mando all'utente l'esito positivo della richiesta di EDIT
                                                Utils.sendBytes(clientSocketChannel, opCode.OP_OK.toString().getBytes());

                                                //mando all'utente l'indirizzo di multicast associato al file
                                                Utils.sendBytes(clientSocketChannel,fileInfo.getAddress().getBytes());

                                                //aumento il numero di collaboratori del file
                                                fileInfo.incCounterEditors();
                                            }
                                            answerCode = opCode.OP_OK;
                                        }
                                        else
                                            answerCode = opCode.SECTION_EDITING;
                                    }
                                    else
                                        answerCode = opCode.OP_OK;
                                }
                                else
                                    answerCode = opCode.ERR_PERMISSION_DENIED;
                                break;
                            }

                            case SECTION_RECEIVE:
                            {
                                String filename = op_in.getFilename();
                                String owner = op_in.getOwner();
                                int section = op_in.getSection();
                                String collectionFilename = filename + "_" + owner;

                                try
                                {
                                    Utils.transferToSection(clientSocketChannel,Utils.getPath(owner,filename,section,true).replaceFirst("./",""));
                                    if(userFiles.get(collectionFilename).isLocked(section-1))
                                        answerCode = opCode.SECTION_EDITING;
                                    else
                                        answerCode = opCode.OP_OK;

                                }
                                catch(IOException ioe)
                                {
                                    answerCode = opCode.OP_FAIL;
                                }
                                break;
                            }

                            case END_EDIT:
                            {
                                String filename = op_in.getFilename();
                                String owner = op_in.getOwner();
                                String collectionFilename = filename + "_" + owner;
                                int section = op_in.getSection();

                                //ricevo il nuovo file dal client
                                try
                                {
                                    Utils.transferFromSection(clientSocketChannel,usr,true);

                                    //tengo traccia del fatto che l'utente non sta più editando
                                    registeredUsers.getUser(usr).setEditingFilename("");
                                    registeredUsers.getUser(usr).setEditingSection(0);

                                    FileInfo fileInfo = userFiles.get(collectionFilename);

                                    //sblocco la sezione che l'utente stava editando
                                    fileInfo.unlockSection(section-1);

                                    //decremento il numero di collaboratori sul file che l'utente stava editando
                                    fileInfo.decCounterEditors();

                                    answerCode = opCode.OP_OK;
                                }
                                catch(IOException ioe)
                                {
                                    answerCode = opCode.OP_FAIL;
                                    break;
                                }

                                break;
                            }

                            case INVITE:
                            {
                                String filename = op_in.getFilename();
                                String owner = op_in.getOwner();
                                String collectionFilename = filename + "_" + owner;


                                UserInfo userInfo = registeredUsers.getUser(usr);

                                if(userInfo == null) //utente inesistente
                                    answerCode = opCode.ERR_USER_UNKNOWN;
                                /*else if(userFiles.get(collectionFilename).getOwner().equals(collaborator))
                                {
                                    answerCode = Utils.opCode.ERR_OWNER_INVITED;
                                }*/
                                else if(userInfo.canEdit(collectionFilename)) //controllo che l'utente non sia già stato invitato
                                {
                                    answerCode = opCode.ERR_USER_ALREADY_INVITED;
                                }
                                else
                                {
                                    userInfo.addFile(collectionFilename);

                                    //utente non online, aggiungo l'invito alla sua lista di inviti pendenti
                                    if(!userInfo.isOnline())
                                    {
                                        Message invitation = new Message(owner, filename, new Date());
                                        userInfo.addPendingInvite(invitation);
                                    }

                                    else
                                    {
                                        //mando in tempo reale l'invito all'utente
                                        Message invitation = new Message(owner, filename, new Date());
                                        Utils.sendObject(userInfo.getInviteSocketChannel(), invitation);
                                    }

                                    answerCode = opCode.OP_OK;
                                }
                                break;
                            }

                            default:
                                break;
                        }

                        key.attach(answerCode);
                        key.interestOps(SelectionKey.OP_WRITE);
                    }
                    else if (key.isWritable()) //sto per scrivere su una socket
                    {
                        SocketChannel clientSocketChannel = (SocketChannel) key.channel();
                        opCode code = (opCode) key.attachment();

                        Utils.sendBytes(clientSocketChannel,code.toString().getBytes());
                        key.interestOps(SelectionKey.OP_READ);
                    }
                }
                catch (IOException ex)
                {
                    key.cancel();
                    try { key.channel().close(); }
                    catch (IOException ioe) {ioe.printStackTrace(); break;}
                }
                iterator.remove();
            }
        }
    }
}