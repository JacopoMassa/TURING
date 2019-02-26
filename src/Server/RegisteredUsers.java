package Server;

import Utils.IntRegistration;

import java.rmi.server.RemoteServer;
import java.util.concurrent.ConcurrentHashMap;

public class RegisteredUsers extends RemoteServer implements IntRegistration
{
    private ConcurrentHashMap<String, UserInfo> users;
    RegisteredUsers()
    {
        this.users = new ConcurrentHashMap<>();
    }

    public boolean registerUser(String username, String password) throws NullPointerException
    {
        if(username == null || password == null)
            throw new NullPointerException();

        if(users.containsKey(username))
            return false;

        UserInfo u = new UserInfo(password);
        users.putIfAbsent(username, u);
        return true;
    }

    UserInfo getUser(String username)
    {
        return users.get(username);
    }

    boolean setStatus(String username, String password, int online)
    {
        if(!checkData(username,password))
            return false;

        if(online==1)
            users.get(username).setOnline();
        else
            users.get(username).setOffline();

        return true;
    }

    /* controlla la consistenza e l'esistenza dell'username e della password */
    private boolean checkData(String username, String password)
    {
        return (username != null && password != null && users.containsKey(username));
    }
}