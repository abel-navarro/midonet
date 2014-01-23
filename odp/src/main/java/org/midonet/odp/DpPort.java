/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;

import com.google.common.base.Function;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.netlink.messages.Builder;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.family.PortFamily;
import org.midonet.odp.flows.FlowActionOutput;
import org.midonet.odp.ports.*;

import static org.midonet.odp.flows.FlowActions.output;

/**
 * Base Datapath Port class.
 */
public abstract class DpPort {

    protected DpPort(@Nonnull String name, @Nonnull Type type) {
        this.name = name;
        this.type = type;
    }

    public enum Type {

        NetDev(OpenVSwitch.Port.Type.Netdev),
        Internal(OpenVSwitch.Port.Type.Internal),
        Gre(OpenVSwitch.Port.Type.Gre),
        VXLan(OpenVSwitch.Port.Type.VXLan),
        Gre64(OpenVSwitch.Port.Type.Gre64),
        Lisp(OpenVSwitch.Port.Type.Lisp);       // not yet supported

        public int attrId;

        Type(int attr) {
            this.attrId = attr;
        }
    }

    protected Integer portNo;
    protected Type type;
    protected String name;
    protected Stats stats;

    public Integer getPortNo() {
        return portNo;
    }

    public void setPortNo(Integer portNo) {
        this.portNo = portNo;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Stats getStats() {
        return stats;
    }

    public void setStats(Stats stats) {
        this.stats = stats;
    }

    public FlowActionOutput toOutputAction() {
      return output(this.portNo.shortValue());
    }

    public void serializeInto(Builder builder) {
        builder.addAttr(PortFamily.Attr.NAME, getName());
        builder.addAttr(PortFamily.Attr.PORT_TYPE, getType().attrId);
        if (getPortNo() != null)
            builder.addAttr(PortFamily.Attr.PORT_NO, getPortNo());
    }

    protected void deserializeFrom(NetlinkMessage msg) {
        this.portNo = msg.getAttrValueInt(PortFamily.Attr.PORT_NO);
        this.stats = Stats.buildFrom(msg);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked") // safe cast
        DpPort port = (DpPort) o;

        if (type != port.type)
            return false;
        if (name != null ? !name.equals(port.name) : port.name != null)
            return false;
        if (portNo != null ? !portNo.equals(port.portNo) : port.portNo != null)
            return false;
        if (stats != null ? !stats.equals(port.stats) : port.stats != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = portNo != null ? portNo.hashCode() : 0;
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + (stats != null ? stats.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DpPort{" +
            "portNo=" + portNo +
            ", type=" + type +
            ", name='" + name + '\'' +
            ", stats=" + stats +
            '}';
    }

    /** Factory method which builds DpPort instances from NetlinkMessages.
     *  Consumes the underlying ByteBuffer.*/
    public static DpPort buildFrom(NetlinkMessage msg) {
        int actualDpIndex = msg.getInt(); // read the datapath id

        String name = msg.getAttrValueString(PortFamily.Attr.NAME);
        Integer type = msg.getAttrValueInt(PortFamily.Attr.PORT_TYPE);
        if (type == null || name == null)
            return null;

        DpPort port = newPortByTypeId(type, name);

        if (port != null)
            port.deserializeFrom(msg);

        return port;
    }

    /** Stateless static deserializer function which builds single ports once
     *  at a time. Consumes the head ByteBuffer of the input List.*/
    public static final Function<List<ByteBuffer>, DpPort> deserializer =
        new Function<List<ByteBuffer>, DpPort>() {
            @Override
            public DpPort apply(List<ByteBuffer> input) {
                if (input == null || input.size() == 0 || input.get(0) == null)
                    return null;
                return DpPort.buildFrom(new NetlinkMessage(input.get(0)));
            }
        };

    /** Stateless static deserializer function which builds sets of ports.
     *  Consumes all ByteBuffer in the input List.*/
    public static final Function<List<ByteBuffer>, Set<DpPort>> setDeserializer =
        new Function<List<ByteBuffer>, Set<DpPort>>() {
            @Override
            public Set<DpPort> apply(List<ByteBuffer> input) {
                Set<DpPort> ports = new HashSet<>();
                if (input == null)
                    return ports;
                for (ByteBuffer buffer : input) {
                    ports.add(DpPort.buildFrom(new NetlinkMessage(buffer)));
                }
                ports.remove(null);
                return ports;
            }
        };

    private static DpPort newPortByTypeId(Integer type, String name) {
        switch (type) {

            case OpenVSwitch.Port.Type.Netdev:
                return new NetDevPort(name);

            case OpenVSwitch.Port.Type.Internal:
                return new InternalPort(name);

            case OpenVSwitch.Port.Type.Gre:
                return new GreTunnelPort(name);

            case OpenVSwitch.Port.Type.Gre64:
                return new GreTunnelPort(name);

            case OpenVSwitch.Port.Type.VXLan:
                return new VxLanTunnelPort(name);

            default:
                return null;
        }
    }

    public static class Stats implements BuilderAware {
        long rxPackets, txPackets;
        long rxBytes, txBytes;
        long rxErrors, txErrors;
        long rxDropped, txDropped;

        public long getRxPackets() {return rxPackets;}
        public long getTxPackets() {return txPackets;}
        public long getRxBytes() {return rxBytes;}
        public long getTxBytes() {return txBytes;}
        public long getRxErrors() {return rxErrors;}
        public long getTxErrors() {return txErrors;}
        public long getRxDropped() {return rxDropped;}
        public long getTxDropped() {return txDropped;}

        @Override
        public void serialize(BaseBuilder<?,?> builder) {
            builder.addValue(rxPackets);
            builder.addValue(txPackets);
            builder.addValue(rxBytes);
            builder.addValue(txBytes);
            builder.addValue(rxErrors);
            builder.addValue(txErrors);
            builder.addValue(rxDropped);
            builder.addValue(txDropped);
        }

        @Override
        public boolean deserialize(NetlinkMessage message) {
            try {
                rxPackets = message.getLong();
                txPackets = message.getLong();
                rxBytes = message.getLong();
                txBytes = message.getLong();
                rxErrors = message.getLong();
                txErrors = message.getLong();
                rxDropped = message.getLong();
                txDropped = message.getLong();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Stats stats = (Stats) o;

            if (rxBytes != stats.rxBytes) return false;
            if (rxDropped != stats.rxDropped) return false;
            if (rxErrors != stats.rxErrors) return false;
            if (rxPackets != stats.rxPackets) return false;
            if (txBytes != stats.txBytes) return false;
            if (txDropped != stats.txDropped) return false;
            if (txErrors != stats.txErrors) return false;
            if (txPackets != stats.txPackets) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = (int) (rxPackets ^ (rxPackets >>> 32));
            result = 31 * result + (int) (txPackets ^ (txPackets >>> 32));
            result = 31 * result + (int) (rxBytes ^ (rxBytes >>> 32));
            result = 31 * result + (int) (txBytes ^ (txBytes >>> 32));
            result = 31 * result + (int) (rxErrors ^ (rxErrors >>> 32));
            result = 31 * result + (int) (txErrors ^ (txErrors >>> 32));
            result = 31 * result + (int) (rxDropped ^ (rxDropped >>> 32));
            result = 31 * result + (int) (txDropped ^ (txDropped >>> 32));
            return result;
        }

        @Override
        public String toString() {
            return "Stats{" +
                "rxPackets=" + rxPackets +
                ", txPackets=" + txPackets +
                ", rxBytes=" + rxBytes +
                ", txBytes=" + txBytes +
                ", rxErrors=" + rxErrors +
                ", txErrors=" + txErrors +
                ", rxDropped=" + rxDropped +
                ", txDropped=" + txDropped +
                '}';
        }

        public static Stats buildFrom(NetlinkMessage msg) {
            return msg.getAttrValue(PortFamily.Attr.STATS, new Stats());
        }
    }
}