package org.fog.test.perfeval;

public class FogNodeWrapper {
    public int id;
    public double txPower;
    public double powerIdle;
    public double CompPower;
    public double capacity;
    public int quota;

    public FogNodeWrapper(int id, double txPower, double idlePower, double busyPower, double capacity, int quota) {
        this.id = id;
        this.txPower = txPower;
        this.powerIdle = idlePower;
        this.CompPower = busyPower;
        this.capacity = capacity;
        this.quota = quota;
    }
    @Override
    public String toString() {
        return "FogNode{" +
                "id='" + (id-4) + '\'' +
                ", txPower=" + txPower +
                
                ",  compPower=" +  CompPower +
                ", capacity=" + capacity +
                ", quota=" +  quota +
                '}';
    }
}
