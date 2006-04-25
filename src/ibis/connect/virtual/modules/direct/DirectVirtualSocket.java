package ibis.connect.virtual.modules.direct;

import ibis.connect.direct.DirectSocket;
import ibis.connect.virtual.VirtualSocket;
import ibis.connect.virtual.VirtualSocketAddress;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.util.Map;

public class DirectVirtualSocket extends VirtualSocket {
    
    protected final DirectSocket s;
    protected final DataOutputStream out;
    protected final DataInputStream in;
    
    protected DirectVirtualSocket(VirtualSocketAddress target, DirectSocket s, 
            DataOutputStream out, DataInputStream in, Map p) {        
        
        super(target);
        
        this.s = s;
        this.out = out;
        this.in = in;               
    }
    
    protected void connectionAccepted() throws IOException { 
        
        try { 
            out.write(Direct.ACCEPT);
            out.flush();        
            
            // Not sure why this is needed...
            //remote = new SocketAddressSet(in.readUTF());
            //remotePort = in.readInt();            
        } catch (IOException e) { 
            Direct.close(s, out, in);  
            throw e;
        } 
    }
    
    // TODO: don't like that this is public!!
    public void connectionRejected() { 
        
        try { 
            out.write(Direct.CONNECTION_REJECTED);
            out.flush();
        } catch (Exception e) {
            // ignore ?
        } finally { 
            Direct.close(s, out, null);
        } 
    }
    
    public void waitForAccept() throws IOException {
        
        try { 
            int result = in.read();
        
            switch (result) {
            case Direct.ACCEPT:
                // TODO: find decent port here ?
                return;
                
            case Direct.PORT_NOT_FOUND:
                throw new SocketException("Remote port not found");                
                
            case Direct.WRONG_MACHINE:            
                throw new SocketException("Connection ended up on wrong machine!");
                
            case Direct.CONNECTION_REJECTED:
                throw new SocketException("Connection rejected");
                
            default:
                throw new SocketException("Got unknown reply during connect!");
            }
            
        } catch (IOException e) {
            // This module worked fine, but we got a 'normal' exception while 
            // connecting (i.e., because the other side refused to connection). 
            // There is no use trying other modules.
            Direct.close(s, out, in);
            throw e;
        }        
    }
    
    public void close() throws IOException {        
        s.close();            
    }
    
    public SocketChannel getChannel() {
        return s.getChannel();
    }
    
    /*
     * public InetAddress getInetAddress() { return s.getInetAddress(); }
     */
    public InputStream getInputStream() throws IOException {
        return in;
    }
    
    public boolean getKeepAlive() throws SocketException {
        return s.getKeepAlive();
    }
    
    /*
     * public InetAddress getLocalAddress() { return s.getLocalAddress(); }
     */
    
    public int getLocalPort() {
        // TODO: is this right ?
        return s.getLocalPort();
    }
    
    public SocketAddress getLocalSocketAddress() {
        // TODO: is this right ?
        return s.getLocalSocketAddress();
    }
          
    public boolean getOOBInline() throws SocketException {
        return s.getOOBInline();
    }
    
    public OutputStream getOutputStream() throws IOException {
        return out;
    }
    
    public int getReceiveBufferSize() throws SocketException {
        return s.getReceiveBufferSize();
    }
    
    public boolean getReuseAddress() throws SocketException {
        return s.getReuseAddress();
    }
    
    public int getSendBufferSize() throws SocketException {
        return s.getSendBufferSize();
    }
    
    public int getSoLinger() throws SocketException {
        return s.getSoLinger();
    }
    
    public int getSoTimeout() throws SocketException {
        return s.getSoTimeout();
    }
    
    public boolean getTcpNoDelay() throws SocketException {
        return s.getTcpNoDelay();
    }
    
    public int getTrafficClass() throws SocketException {
        return s.getTrafficClass();
    }
    
    public boolean isBound() {
        return true;
    }
    
    public boolean isClosed() {
        return s.isClosed();
    }
    
    public boolean isConnected() {
        return s.isConnected();
    }
    
    public boolean isInputShutdown() {
        return s.isInputShutdown();
    }
    
    public boolean isOutputShutdown() {
        return s.isOutputShutdown();
    }
    
    public void sendUrgentData(int data) throws IOException {
        s.sendUrgentData(data);
    }
    
    public void setKeepAlive(boolean on) throws SocketException {
        s.setKeepAlive(on);
    }
    
    public void setOOBInline(boolean on) throws SocketException {
        s.setOOBInline(on);
    }
    
    public void setReceiveBufferSize(int sz) throws SocketException {
        s.setReceiveBufferSize(sz);
    }
    
    public void setReuseAddress(boolean on) throws SocketException {
        s.setReuseAddress(on);
    }
    
    public void setSendBufferSize(int sz) throws SocketException {
        s.setSendBufferSize(sz);
    }
    
    public void setSoLinger(boolean on, int linger) throws SocketException {
        s.setSoLinger(on, linger);
    }
    
    public void setSoTimeout(int t) throws SocketException {
        s.setSoTimeout(t);
    }
    
    public void setTcpNoDelay(boolean on) throws SocketException {
        s.setTcpNoDelay(on);
    }
    
    public void setTrafficClass(int tc) throws SocketException {
        s.setTrafficClass(tc);
    }
    
    public void shutdownInput() throws IOException {
        s.shutdownInput();
    }
    
    public void shutdownOutput() throws IOException {
        s.shutdownOutput();
    }
    
    public String toString() {
        return "DirectVirtualIbisSocket(" + s.toString() + ")";
    }   
}
