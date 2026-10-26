package local.tv.bluehome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/** A durable shuffle bag. Pure Java so boot, process-restart and corrupt-entry rules can be tested. */
final class OfflineRotationPolicy {
    static final int MAX_ENTRIES=36;
    static final class State {
        final String currentId,appliedCycle,remainingBagCsv;
        State(String currentId,String appliedCycle,String remainingBagCsv){
            this.currentId=currentId==null?"":currentId;
            this.appliedCycle=appliedCycle==null?"":appliedCycle;
            this.remainingBagCsv=remainingBagCsv==null?"":remainingBagCsv;
        }
    }
    private OfflineRotationPolicy() { }
    static boolean validId(String id){return id!=null&&id.matches("[a-zA-Z0-9_-]{1,40}");}
    static State next(State prior,List<String> availableIds,String observedCycle,boolean enabled,Random random){
        if(prior==null)prior=new State("","","");
        if(observedCycle==null)observedCycle="";
        LinkedHashSet<String> unique=new LinkedHashSet<String>();
        if(availableIds!=null)for(String id:availableIds)if(validId(id)&&unique.size()<MAX_ENTRIES)unique.add(id);
        ArrayList<String> available=new ArrayList<String>(unique);
        ArrayList<String> bag=new ArrayList<String>();
        for(String id:prior.remainingBagCsv.split(","))if(unique.contains(id)&&!bag.contains(id))bag.add(id);
        boolean currentExists=unique.contains(prior.currentId);
        // Persisting the observed cycle while paused also prevents a mode toggle from acting like a boot.
        if(currentExists&&(!enabled||observedCycle.equals(prior.appliedCycle)))
            return new State(prior.currentId,observedCycle,join(bag));
        if(available.isEmpty())return new State("",observedCycle,"");
        if(!enabled){
            String first=available.get(0);available.remove(0);
            return new State(first,observedCycle,join(available));
        }
        // A stale/edited bag must never repeat the still-current picture at its head.
        bag.remove(prior.currentId);
        if(bag.isEmpty()){
            bag.addAll(available);Collections.shuffle(bag,random==null?new Random():random);
            if(bag.size()>1&&bag.get(0).equals(prior.currentId)){
                int swap=1+(random==null?0:random.nextInt(bag.size()-1));Collections.swap(bag,0,swap);
            }
        }
        String selected=bag.remove(0);
        return new State(selected,observedCycle,join(bag));
    }
    static String join(List<String> ids){
        StringBuilder value=new StringBuilder();for(String id:ids){if(value.length()>0)value.append(',');value.append(id);}return value.toString();
    }
}
