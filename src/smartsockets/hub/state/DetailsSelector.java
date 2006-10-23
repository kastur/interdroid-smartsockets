package smartsockets.hub.state;

import java.util.LinkedList;

public class DetailsSelector extends Selector {
    
    private LinkedList result = new LinkedList();
    
    public boolean needAll() {
        return true;
    }
    
    public void select(HubDescription description) {
        
        StringBuffer tmp = new StringBuffer("HubInfo(");

        tmp.append(description.hubAddressAsString);
        tmp.append(",");
        tmp.append(description.getHomeState());        
        tmp.append(",");
        tmp.append(description.numberOfClients());                
        tmp.append(",");    
        
        String [] con = description.connectedTo();
        
        if (con == null) { 
            tmp.append("0");               
        } else { 
            tmp.append(con.length);
            
            for (int i=0;i<con.length;i++) {
                tmp.append(",");
                tmp.append(con[i]);
            }            
        } 
          
        tmp.append(")");
        
        result.add(tmp.toString());
    }
    
    public LinkedList getResult() { 
        return result;
    }   
}