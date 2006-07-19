/**
 * 
 */
package ibis.connect.gossipproxy.connections;

import ibis.connect.direct.DirectSocket;
import ibis.connect.direct.DirectSocketFactory;
import ibis.connect.direct.SocketAddressSet;
import ibis.connect.gossipproxy.ProxyDescription;
import ibis.connect.gossipproxy.ProxyList;
import ibis.connect.gossipproxy.ProxyProtocol;
import ibis.connect.util.Forwarder;
import ibis.connect.util.ForwarderDoneCallback;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;

public class ForwarderConnection extends BaseConnection 
    implements ForwarderDoneCallback { 
    
    private static final int DEFAULT_TIMEOUT = 10000;

    private int number; 
    
    private String id; 
    
    private String clientAsString;    
    //DirectSocket socketA;
    //InputStream inA;
    //OutputStream outA;        
    
    private String targetAsString;
    private DirectSocket socketB;        
    private InputStream inB;
    private OutputStream outB;
    
    private Forwarder forwarder1; // forwards from inA to outB
    private String label1;
        
    private Forwarder forwarder2; // forwards from inB to outA
    private String label2;
    
    private int done = 0;
    
    private LinkedList skipProxies;
    
    private DirectSocketFactory factory;
    
    public ForwarderConnection(DirectSocket s, DataInputStream in, DataOutputStream out, 
            Connections connections, ProxyList proxies, String clientAsString, 
            String targetAsString, int number, LinkedList skipProxies) { 
       
        super(s, in, out, connections, proxies);
        
        factory = DirectSocketFactory.getSocketFactory();
        
        id = "[" + number + ": " + clientAsString + " <--> " + targetAsString + "]";

        this.number = number;
        
        this.clientAsString = clientAsString;
        this.targetAsString = targetAsString;            
        this.skipProxies = skipProxies;       
    }
            
    public String toString() { 
        return id;         
    }
    
    public String getName() {
        return "ForwarderConnection(" + id + ")";
    }
   
    public synchronized void done(String label) {
        
        logger.info("Received callback for forwarder " + label);
        
        // Check which forwarder thread produced the callback. Should be a 
        // real reference to label, so we don't have to use equals.
        if (label == label1) {
            try { 
                outB.flush();
                socketB.shutdownOutput();            
                s.shutdownInput();
            } catch (Exception e) {
                logger.warn("Failed to properly shutdown " + label1, e);                    
            }
        }
        
        if (label == label2) {
            try { 
                out.flush();
                s.shutdownOutput();            
                socketB.shutdownInput();
            } catch (Exception e) {
                logger.warn("Failed to properly shutdown " + label2, e);
            }
        }
    
        done++; 
        
        if (done == 2) { 
            logger.info("Removing connection " + id + " since it is done!");
            
            DirectSocketFactory.close(s, out, in);
            DirectSocketFactory.close(socketB, outB, inB);
            
            connections.removeConnection(id);
        } else { 
            logger.info("Cannot remove connection " + id + " yet!");
        }        
    }          
    
    private void startForwarding() { 
        label1 = "[" + number + ": " + clientAsString + " --> "
            + targetAsString + "]";
    
        label2 = "[" + number + ": " + clientAsString + " <-- "
            + targetAsString + "]";
            
        // Create the forwarders and start them
        forwarder1 = new Forwarder(in, outB, this, label1);
        forwarder2 = new Forwarder(inB, out, this, label2);
              
        new Thread(forwarder1, label1).start();
        new Thread(forwarder2, label2).start();        

        logger.info("Connection forwarders started!");
    }
    
    
    private boolean connectToProxy(ProxyDescription p)  { 

        logger.info("Attempting to connect to proxy " + p.proxyAddress);
        
        try { 
            socketB = factory.createSocket(p.proxyAddress, DEFAULT_TIMEOUT, null);  
            
            if (socketB == null) { 
                logger.info("Failed to connect to proxy " + p.proxyAddress);
                return false;
            }     
            
            outB = socketB.getOutputStream();            
            inB = socketB.getInputStream();

            DataOutputStream dout = new DataOutputStream(outB);
            dout.writeByte(ProxyProtocol.PROXY_CLIENT_CONNECT);
            dout.writeUTF(clientAsString);
            dout.writeUTF(targetAsString);
            dout.writeInt(0);
            dout.flush();
            
            int result = inB.read();
            
            if (result == ProxyProtocol.REPLY_CLIENT_CONNECTION_ACCEPTED) { 
                logger.info("Remote proxy connected to client!");
                return true;
            } else { 
                logger.info("Remote proxy failed to connected to client!");
            }
            
        } catch (Exception e) {
            logger.info("Got exception: " + e, e);                
        }

        // We can only end up here if the connection setup failed!
        DirectSocketFactory.close(socketB, outB, inB);
        
        socketB = null;
        outB = null;
        inB = null;
        
        return false;
    }
    
    private boolean directConnection(ProxyDescription p, int currentHops) {  
    
        // Create the connection
        if (connectToProxy(p)) {  
            logger.info("Connection " + id + " created!");
            
            try {                
                out.write(ProxyProtocol.REPLY_CLIENT_CONNECTION_ACCEPTED);
                out.flush();
                startForwarding();                        
                return true;

            } catch (Exception e) {
                logger.info("Connection setup to " + targetAsString + " failed", e);            
                DirectSocketFactory.close(socketB, outB, inB);            
            }
        } 
          
        logger.info("Connection setup to " + targetAsString + " failed");
        return false;
    }
    
    private boolean reverseConnection(ProxyDescription p, int currentHops) {
        
        try {
            String target = p.proxyAddressAsString;
            
            ProxyConnection conn = 
                (ProxyConnection) connections.getConnection(target);

            if (conn == null) { 
                return false;
            }
            
            String a = knownProxies.getLocalDescription().proxyAddressAsString;             
            conn.writeMessage(false, a, target, "module", 42, "message", 1);
                        
        } catch (Exception e) {
            logger.debug("Reverse connection failed!", e);
        }
        
        return false;
    } 
        
    private boolean connectViaProxy(ProxyDescription p, int currentHops, 
            boolean allowIndirection) {                
        
        logger.info("Attempting to connect to client " + targetAsString 
                + " via proxy " + p.proxyAddress);

        // Check if we can directly reach the proxy, or if we don't known if it 
        // is reachable yet ... If so, we try a direct connection. 
        if (p.directlyReachable() || !p.reachableKnown()) {             
            if (directConnection(p, currentHops)) { 
                return true;
            } 
        }

        // If a direct connection was not possible or failed, a reverse 
        // connection may be work. 
        if (p.canReachMe() || !p.canReachMeKnown()) { 
            if (reverseConnection(p, currentHops)) { 
                return true;
            }
        }

        // If neither a direct or reverse connection was possible, we try an 
        // indirect connection, but only if we are allowed to do so... 
        if (allowIndirection) { 
            SocketAddressSet indirection = p.getIndirection();
        
            if (indirection == null) { 
                return false;
            }
            
            ProxyDescription p2 = knownProxies.get(indirection);
            
            if (p2 == null) { 
                return false;                
            }
            
            return connectViaProxy(p2, currentHops, false);                        
        }
        
        return false;
    }

    private boolean connectToClient() {        
        
        try {
            logger.info("Attempting to connect to client " + targetAsString);
            
            SocketAddressSet target = new SocketAddressSet(targetAsString);
            
            // Create the connection
            socketB = factory.createSocket(target, DEFAULT_TIMEOUT, null); 
            
            outB = socketB.getOutputStream();            
            inB = socketB.getInputStream();

            logger.info("Connection " + id + " created!");
                        
            out.write(ProxyProtocol.REPLY_CLIENT_CONNECTION_ACCEPTED);
            out.flush();
    
            startForwarding();
                        
            return true;
        } catch (Exception e) {
            logger.info("Connection setup to " + targetAsString + " failed", e);            
            DirectSocketFactory.close(socketB, outB, inB);            
        }
        
        return false;
    }
        
    protected boolean runConnection() {
    
        // See if we known any proxies that know the target machine. Note that 
        // if the local proxy is able to connect to the client, it will be
        // returned at the head of the list (so we try it first).    
        LinkedList proxies = knownProxies.findClient(targetAsString, skipProxies);
        
        logger.info("Found " + proxies.size() + " proxies that know " 
                + targetAsString);            
        
        for (int i=0;i<proxies.size();i++) { 
            ProxyDescription p = (ProxyDescription) proxies.removeFirst();
            
            if (p.isLocal()) { 
                if (connectToClient()) { 
                    logger.info("Succesfully created direct connection to " 
                            + targetAsString);
                    // Succesfully connected to client!                 
                    return false;                        
                } else { 
                    logger.info("Failed to create direct connection to " 
                            + targetAsString);                    
                }
            } else {
                if (connectViaProxy(p, 0, true)) { 
                    logger.info("Succesfully created indirect connection to " 
                            + targetAsString + " via proxy " + p.proxyAddress);
                    return false;                        
                } else { 
                    logger.info("Failed to created indirect connection to " 
                            + targetAsString + " via proxy " + p.proxyAddress);                    
                }
            }
        }
     
        logger.info("Failed to create any connection to " + targetAsString);            
        
        try { 
            // Failed to connect, so give up....
            out.write(ProxyProtocol.REPLY_CLIENT_CONNECTION_DENIED);
            out.flush();
        } catch (Exception e) {
            logger.warn("Failed to send reply to client!", e);
        } finally { 
            DirectSocketFactory.close(s, out, in);
        }
        
        return false;
    }    
}