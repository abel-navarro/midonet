/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.action.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.actors.threadpool.Arrays;

import com.midokura.midolman.ForwardingElement.Action;
import com.midokura.midolman.ForwardingElement.ForwardInfo;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.ReplicatedRoutingTable;
import com.midokura.midolman.layer3.Router;
import com.midokura.midolman.layer3.ServiceFlowController;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.*;
import com.midokura.midolman.portservice.PortService;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.IPv4Set;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortSetMap;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.Callback;
import com.midokura.midolman.util.Net;
import com.midokura.midolman.util.ShortUUID;

public class VRNController extends AbstractController
    implements ServiceFlowController {

    private static final Logger log = LoggerFactory
            .getLogger(VRNController.class);

    // TODO(pino): This constant should be declared in openflow...
    public static final short NO_HARD_TIMEOUT = 0;
    public static final short NO_IDLE_TIMEOUT = 0;
    // TODO(pino)
    public static final short ICMP_EXPIRY_SECONDS = 5;
    private static final short FLOW_PRIORITY = 0;
    private static final short SERVICE_FLOW_PRIORITY = FLOW_PRIORITY + 1;
    public static final int ICMP_TUNNEL = 0x05;

    private PortZkManager portMgr;
    private RouteZkManager routeMgr;
    VRNCoordinator vrn;
    private Map<UUID, L3DevicePort> devPortById;
    private Map<Integer, L3DevicePort> devPortByNum;
    short idleFlowExpireSeconds; //package private to allow test access.

    private PortService service;
    private Map<UUID, List<Runnable>> portServicesById;
    // Store port num of a port that has a service port.
    private short serviceTargetPort;
    // Track which routers processed an installed flow.
    private Map<MidoMatch, Set<UUID>> matchToRouters;
    // The controllers which make up the portsets.
    // TODO: Should this be part of PortZkManager?
    private PortSetMap portSetMap;
    // The local OVS ports in a portset.
    private Map<UUID, Set<Short>> localPortSetSlices;
    private DhcpHandler dhcpHandler;

    public VRNController(long datapathId, UUID deviceId, int greKey,
            PortToIntNwAddrMap dict, short idleFlowExpireSeconds,
            IntIPv4 localNwAddr, PortZkManager portMgr,
            RouterZkManager routerMgr, RouteZkManager routeMgr,
            ChainZkManager chainMgr, RuleZkManager ruleMgr,
            OpenvSwitchDatabaseConnection ovsdb, Reactor reactor, Cache cache,
            String externalIdKey, PortService service, PortSetMap portSetMap) {
        super(datapathId, deviceId, greKey, ovsdb, dict, localNwAddr,
              externalIdKey);
        this.idleFlowExpireSeconds = idleFlowExpireSeconds;
        this.portMgr = portMgr;
        this.routeMgr = routeMgr;
        this.vrn = new VRNCoordinator(deviceId, portMgr, routerMgr, chainMgr,
                ruleMgr, reactor, cache);
        this.devPortById = new HashMap<UUID, L3DevicePort>();
        this.devPortByNum = new HashMap<Integer, L3DevicePort>();
        this.portSetMap = portSetMap;
        portSetMap.start();
        this.localPortSetSlices = new HashMap<UUID, Set<Short>>();

        this.service = service;
        this.service.setController(this);
        this.portServicesById = new HashMap<UUID, List<Runnable>>();
        this.matchToRouters = new HashMap<MidoMatch, Set<UUID>>();
        this.dhcpHandler = new DhcpHandler();
    }

    /*
     * Setup a flow that sends all DHCP request packets to
     * the controller.
     */
    private void setFlowsForHandlingDhcpInController(short portNum) {
        log.debug("setFlowsForHandlingDhcpInController: on port {}", portNum);

        MidoMatch match = new MidoMatch();
        match.setInputPort(portNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(UDP.PROTOCOL_NUMBER);
        match.setTransportSource((short) 68);
        match.setTransportDestination((short) 67);

        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(OFPort.OFPP_CONTROLLER.getValue(),
                (short) 1024));

        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);
    }


    public void addGeneratedPacket(Ethernet pkt, UUID originPort) {
        //reactor.submit(new GenPacketContext(pkt, originPort));
    }

    public void continueProcessing(ForwardInfo info) {
        /*reactor.schedule(new Runnable() {
           public void run() {
               //vrn.handleProcessResult(ctx);
               //VRNController.handleSimulationResult(ctx);
           }
        });*/
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short shortInPort,
            byte[] data, long matchingTunnelId) {
        int inPort = shortInPort & 0xffff;
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, shortInPort);
        L3DevicePort devPortOut;

        // Rewrite inPort with the service's target port assuming that
        // service flows sent this packet to the OFPP_CONTROLLER.
        // TODO(yoshi): replace this with better mechanism such as ARP proxy
        // for service ports.
        if (inPort == (OFPort.OFPP_LOCAL.getValue() & 0xffff)) {
            log.debug("onPacketIn: rewrite port {} to {}", inPort,
                      serviceTargetPort);
            inPort = serviceTargetPort;
        }

        // Try mapping the port number to a virtual device port.
        L3DevicePort devPortIn = devPortByNum.get(inPort);
        // If the port isn't a virtual port and it isn't a tunnel, drop the pkt.
        if (null == devPortIn && !super.isTunnelPortNum(inPort)) {
            log.warn("onPacketIn: dropping packet from port {} (not virtual "
                    + "or tunnel): {}", inPort, match);
            // TODO(pino): should we install a drop rule to avoid processing
            // all the packets from this port?
            freeBuffer(bufferId);
            return;
        }

        ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
        Ethernet ethPkt = new Ethernet();
        try {
            ethPkt.deserialize(bb);
        } catch(MalformedPacketException ex) {
            // Packet could not be deserialized: Drop it.
            log.warn("onPacketIn: dropping malformed packet from port {}.  "
                    + "{}", inPort, ex.getMessage());
            freeBuffer(bufferId);
            return;
        }

        log.debug("onPacketIn: port {} received buffer {} of size {} - {}",
                new Object [] { inPort, bufferId, totalLen, ethPkt });

        if (super.isTunnelPortNum(inPort)) {
            log.debug("onPacketIn: got packet from tunnel {}", inPort);

            // TODO: Check for multicast packets we generated ourself for a
            // group we're in, and drop them.

            // TODO: Check for the broadcast address, and if so use the
            // broadcast
            // ethernet address for the dst MAC.
            // We can check the broadcast address by looking up the gateway in
            // Zookeeper to get the prefix length of its network.

            // TODO: Do address spoofing prevention: if the source
            // address doesn't match the vport's, drop the flow.

            // Extract the gateway IP and vport uuid.
            DecodedMacAddrs portsAndGw = decodeMacAddrs(match
                    .getDataLayerSource(), match.getDataLayerDestination());

            log.debug("onPacketIn: from tunnel port {} decoded mac {}",
                    inPort, portsAndGw);

            // If we don't own the egress port, there was a forwarding mistake.
            devPortOut = devPortById.get(portsAndGw.lastEgressPortId);

            if (null == devPortOut) {
                log.warn("onPacketIn: the egress port {} is not local", portsAndGw.lastEgressPortId);
                // TODO: raise an exception or install a Blackhole?
                return;
            }

            // XXX: There will be no more TunneledPktArpCallbacks
            TunneledPktArpCallback cb = new TunneledPktArpCallback(bufferId,
                    totalLen, inPort, data, match, portsAndGw);
            log.debug("onPacketIn: need mac for ip {} on port {}",
                        IPv4.fromIPv4Address(portsAndGw.nextHopNwAddr),
                        portsAndGw.lastEgressPortId);
            // The ARP will be completed asynchronously by the callback.
            return;
        }

        // check if the packet is a DHCP request
        if (ethPkt.getEtherType() == IPv4.ETHERTYPE) {
            IPv4 ipv4 = (IPv4) ethPkt.getPayload();
            if (ipv4.getProtocol() == UDP.PROTOCOL_NUMBER) {
                UDP udp = (UDP) ipv4.getPayload();
                if (udp.getSourcePort() == 68 && udp.getDestinationPort() == 67) {
                    DHCP dhcp = (DHCP) udp.getPayload();
                    if (dhcp.getOpCode() == DHCP.OPCODE_REQUEST) {
                        log.debug("onPacketIn: got a DHCP bootrequest");
                        dhcpHandler.handleDhcpRequest(devPortIn, dhcp,
                                ethPkt.getSourceMACAddress());
                        freeBuffer(bufferId);
                        return;
                    }
                }
            }
        }

        // Drop the packet if it's not addressed to an L2 mcast address or
        // the ingress port's own address.
        // TODO(pino): check this with Jacob.
        if (!ethPkt.getDestinationMACAddress().equals(devPortIn.getMacAddr())
                && !ethPkt.isMcast()) {
            log.warn("onPacketIn: dlDst {} not mcast nor virtual port's addr", ethPkt.getDestinationMACAddress());
            installBlackhole(match, bufferId, NO_IDLE_TIMEOUT, ICMP_EXPIRY_SECONDS);
            return;
        }
        ForwardInfo fwdInfo = new ForwardInfo();
        fwdInfo.inPortId = devPortIn.getId();
        fwdInfo.flowMatch = match;
        fwdInfo.matchIn = match.clone();
        fwdInfo.pktIn = ethPkt;
        Set<UUID> routers = new HashSet<UUID>();
        // TODO(pino, jlm): make sure notifyFEs is used in the rest of the class
        fwdInfo.notifyFEs = routers;
        try {
            vrn.process(fwdInfo);
        } catch (Exception e) {
            log.warn("onPacketIn dropping packet: ", e);
            freeBuffer(bufferId);
            freeFlowResources(match, routers);
            return;
        }
        boolean useWildcards = false; // TODO(pino): replace with real config.

        MidoMatch flowMatch;
        switch (fwdInfo.action) {
        case BLACKHOLE:
            // TODO(pino): the following wildcarding seems too aggressive.
            // If wildcards are enabled, wildcard everything but nw_src and
            // nw_dst.
            // This is meant to protect against DOS attacks by preventing ipc's
            // to
            // the Openfaucet controller if mac addresses or tcp ports are
            // cycled.
            if (useWildcards)
                flowMatch = makeWildcarded(match);
            else
                flowMatch = match;
            log.debug("onPacketIn: vrn.process() returned BLACKHOLE for {}", fwdInfo);
            installBlackhole(flowMatch, bufferId, NO_IDLE_TIMEOUT,
                    ICMP_EXPIRY_SECONDS);
            notifyFlowAdded(match, flowMatch, devPortIn.getId(), fwdInfo,
                    routers);
            freeFlowResources(match, routers);
            return;
        case CONSUMED:
            log.debug("onPacketIn: vrn.process() returned CONSUMED for {}", fwdInfo);
            freeBuffer(bufferId);
            return;
        case FORWARD:
            // If the egress port is local, ARP and forward the packet.
            devPortOut = devPortById.get(fwdInfo.outPortId);
            if (null != devPortOut) {
                log.debug("onPacketIn: vrn.process() returned FORWARD to " +
                          "local port {} for {}", devPortOut, fwdInfo);
                // XXX: There'll be no more LocalPktArpCallbacks.
                LocalPktArpCallback cb = new LocalPktArpCallback(bufferId,
                        totalLen, devPortIn, data, match, fwdInfo, ethPkt,
                        routers);
            } else { // devPortOut is null; the egress port is remote or multiple.
                log.debug("onPacketIn: vrn.process() returned FORWARD to "
                        + "remote/multi port {} for {}", fwdInfo.outPortId, fwdInfo);

                if (portSetMap.containsKey(fwdInfo.outPortId)) {
                    Set<Short> outPorts = new HashSet<Short>();
                    // Add local OVS ports.
                    if (localPortSetSlices.containsKey(fwdInfo.outPortId))
                        outPorts.addAll(localPortSetSlices.get(fwdInfo.outPortId));
                    IPv4Set remoteControllersAddrs = portSetMap.get(fwdInfo.outPortId);
                    if (remoteControllersAddrs == null)
                        log.error("Can't find portset ID {}", fwdInfo.outPortId);
                    else for (String remoteControllerAddr :
                            remoteControllersAddrs.getStrings()) {
                        IntIPv4 target = IntIPv4.fromString(remoteControllerAddr);
                        // Skip the local controller.
                        if (target.equals(publicIp))
                            continue;
                        Integer portNum = tunnelPortNumOfPeer(target);
                        if (portNum != null) {
                            outPorts.add(new Short(portNum.shortValue()));
                        } else {
                            log.warn("onPacketIn:  No OVS tunnel port found " +
                                     "for Controller at {}", remoteControllerAddr);
                        }
                    }

                    if (outPorts.size() == 0) {
                        log.warn("onPacketIn:  No OVS ports or tunnels found " +
                                 "for portset {}", fwdInfo.outPortId);

                        installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                                ICMP_EXPIRY_SECONDS);
                        freeFlowResources(match, routers);
                        // TODO: check whether this is the right error code (host?).
                        //sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_NET,
                        //        devPortIn.getId(), ethPkt, fwdInfo.inPortId,
                        //        fwdInfo.pktIn, fwdInfo.outPortId);
                        return;
                    }

                    List<OFAction> ofActions = makeActionsForFlow(match,
                            fwdInfo.matchOut, outPorts);
                    // Track the routers for this flow so we can free resources
                    // when the flow is removed.
                    matchToRouters.put(match, routers);
                    addFlowAndSendPacket(bufferId, match, idleFlowExpireSeconds,
                            NO_HARD_TIMEOUT, true, ofActions, inPort, data);
                    return;
                }

                Integer tunPortNum =
                        super.portUuidToTunnelPortNumber(fwdInfo.outPortId);
                if (null == tunPortNum) {
                    log.warn("onPacketIn:  No tunnel port found for {}",
                            fwdInfo.outPortId);

                    installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                            ICMP_EXPIRY_SECONDS);
                    freeFlowResources(match, routers);
                    // TODO: check whether this is the right error code (host?).
                    //sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_NET,
                    //        devPortIn.getId(), ethPkt, fwdInfo.inPortId,
                    //        fwdInfo.pktIn, fwdInfo.outPortId);
                    return;
                }

                log.debug("onPacketIn: FORWARDing to tunnel port {}",
                        tunPortNum);
                MAC[] dlHeaders = getDlHeadersForTunnel(
                        ShortUUID.UUID32toInt(fwdInfo.inPortId),
                        ShortUUID.UUID32toInt(fwdInfo.outPortId),
                        fwdInfo.nextHopNwAddr);

                fwdInfo.matchOut.setDataLayerSource(dlHeaders[0]);
                fwdInfo.matchOut.setDataLayerDestination(dlHeaders[1]);

                List<OFAction> ofActions = makeActionsForFlow(match,
                        fwdInfo.matchOut, tunPortNum.shortValue());
                // TODO(pino): should we do any wildcarding here?
                // Track the routers for this flow so we can free resources
                // when the flow is removed.
                matchToRouters.put(match, routers);
                addFlowAndSendPacket(bufferId, match, idleFlowExpireSeconds,
                        NO_HARD_TIMEOUT, true, ofActions, inPort, data);
            }
            return;
        case NOT_IPV4:
            log.debug("onPacketIn: vrn.process() returned NOT_IPV4, " +
                      "ethertype is {}", match.getDataLayerType());
            // Wildcard everything but dl_type. One rule per EtherType.
            short dlType = match.getDataLayerType();
            match = new MidoMatch();
            match.setDataLayerType(dlType);
            installBlackhole(match, bufferId, NO_IDLE_TIMEOUT ,NO_HARD_TIMEOUT);
            return;
        case NO_ROUTE:
            log.debug("onPacketIn: vrn.process() returned NO_ROUTE for {}",
                    fwdInfo);
            // Intentionally use an exact match for this drop rule.
            // TODO(pino): wildcard the L2 fields.
            installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                    ICMP_EXPIRY_SECONDS);
            freeFlowResources(match, routers);
            // Send an ICMP
            //sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_NET, devPortIn
            //        .getId(), ethPkt, fwdInfo.inPortId, fwdInfo.pktIn,
            //        fwdInfo.outPortId);
            // This rule is temporary, don't notify the flow checker.
            return;
        case REJECT:
            log.debug("onPacketIn: vrn.process() returned REJECT for {}",
                    fwdInfo);
            // Intentionally use an exact match for this drop rule.
            installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                    ICMP_EXPIRY_SECONDS);
            freeFlowResources(match, routers);
            // Send an ICMP
            //sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_FILTER_PROHIB,
            //        devPortIn.getId(), ethPkt, fwdInfo.inPortId, fwdInfo.pktIn,
            //        fwdInfo.outPortId);
            // This rule is temporary, don't notify the flow checker.
            return;
        default:
            log.error("onPacketIn: vrn.process() returned unrecognized action {}",
                    fwdInfo.action);
            throw new RuntimeException("Unrecognized forwarding Action type " + fwdInfo.action);
        }
    }

    private List<OFAction> makeActionsForFlow(MidoMatch origMatch,
            MidoMatch newMatch, short outPortNum) {
        Set<Short> portSet = new HashSet<Short>();
        portSet.add(outPortNum);
        return makeActionsForFlow(origMatch, newMatch, portSet);
    }

    private List<OFAction> makeActionsForFlow(MidoMatch origMatch,
            MidoMatch newMatch, Set<Short> outPorts) {
        // Create OF actions for fields that changed from original to last
        // match.
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction action = null;
        if (!Arrays.equals(origMatch.getDataLayerSource(), newMatch
                .getDataLayerSource())) {
            action = new OFActionDataLayerSource();
            ((OFActionDataLayer) action).setDataLayerAddress(newMatch
                    .getDataLayerSource());
            actions.add(action);
        }
        if (!Arrays.equals(origMatch.getDataLayerDestination(), newMatch
                .getDataLayerDestination())) {
            action = new OFActionDataLayerDestination();
            ((OFActionDataLayer) action).setDataLayerAddress(newMatch
                    .getDataLayerDestination());
            actions.add(action);
        }
        if (origMatch.getNetworkSource() != newMatch.getNetworkSource()) {
            action = new OFActionNetworkLayerSource();
            ((OFActionNetworkLayerAddress) action).setNetworkAddress(newMatch
                    .getNetworkSource());
            actions.add(action);
        }
        if (origMatch.getNetworkDestination() != newMatch
                .getNetworkDestination()) {
            action = new OFActionNetworkLayerDestination();
            ((OFActionNetworkLayerAddress) action).setNetworkAddress(newMatch
                    .getNetworkDestination());
            actions.add(action);
        }
        if (origMatch.getTransportSource() != newMatch.getTransportSource()) {
            action = new OFActionTransportLayerSource();
            ((OFActionTransportLayer) action).setTransportPort(newMatch
                    .getTransportSource());
            actions.add(action);
        }
        if (origMatch.getTransportDestination() != newMatch
                .getTransportDestination()) {
            action = new OFActionTransportLayerDestination();
            ((OFActionTransportLayer) action).setTransportPort(newMatch
                    .getTransportDestination());
            actions.add(action);
        }
        for (Short outPortNum : outPorts) {
            action = new OFActionOutput(outPortNum.shortValue(), (short) 0);
            actions.add(action);
        }
        return actions;
    }

    private MidoMatch makeWildcardedFromTunnel(MidoMatch m1) {
        // TODO Auto-generated method stub
        return m1;
    }

    public static MAC[] getDlHeadersForTunnel(
            int lastInPortId, int lastEgPortId, int gwNwAddr) {
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];

        // Set the data layer source and destination:
        // The ingress port is used as the high 32 bits of the source mac.
        // The egress port is used as the low 32 bits of the dst mac.
        // The high 16 bits of the gwNwAddr are the low 16 bits of the src mac.
        // The low 16 bits of the gwNwAddr are the high 16 bits of the dst mac.
        for (int i = 0; i < 4; i++)
            dlSrc[i] = (byte) (lastInPortId >> (3 - i) * 8);
        dlSrc[4] = (byte) (gwNwAddr >> 24);
        dlSrc[5] = (byte) (gwNwAddr >> 16);
        dlDst[0] = (byte) (gwNwAddr >> 8);
        dlDst[1] = (byte) (gwNwAddr);
        for (int i = 2; i < 6; i++)
            dlDst[i] = (byte) (lastEgPortId >> (5 - i) * 8);

        return new MAC[] {new MAC(dlSrc), new MAC(dlDst)};
    }

    public static class DecodedMacAddrs {
        UUID lastIngressPortId;
        UUID lastEgressPortId;
        int nextHopNwAddr;

        public String toString() {
            return String.format("DecodedMacAddrs: ingress %s egress %s nextHopIp %s",
                    lastIngressPortId,
                    lastEgressPortId,
                    Net.convertIntAddressToString(nextHopNwAddr));
        }
    }

    public static DecodedMacAddrs decodeMacAddrs(final byte[] src,
            final byte[] dst) {
        DecodedMacAddrs result = new DecodedMacAddrs();
        int port32BitId = 0;
        for (int i = 0; i < 4; i++)
            port32BitId |= (src[i] & 0xff) << ((3 - i) * 8);
        result.lastIngressPortId = ShortUUID.intTo32BitUUID(port32BitId);
        result.nextHopNwAddr = (src[4] & 0xff) << 24;
        result.nextHopNwAddr |= (src[5] & 0xff) << 16;
        result.nextHopNwAddr |= (dst[0] & 0xff) << 8;
        result.nextHopNwAddr |= (dst[1] & 0xff);
        port32BitId = 0;
        for (int i = 2; i < 6; i++)
            port32BitId |= (dst[i] & 0xff) << (5 - i) * 8;
        result.lastEgressPortId = ShortUUID.intTo32BitUUID(port32BitId);
        return result;
    }

    private class TunneledPktArpCallback implements Callback<MAC> {
        public TunneledPktArpCallback(int bufferId, int totalLen, int inPort,
                byte[] data, MidoMatch match, DecodedMacAddrs portsAndGw) {
            super();
            this.bufferId = bufferId;
            this.totalLen = totalLen;
            this.inPort = inPort;
            this.data = data;
            this.match = match;
            this.portsAndGw = portsAndGw;
        }

        int bufferId;
        int totalLen;
        int inPort;
        byte[] data;
        MidoMatch match;
        DecodedMacAddrs portsAndGw;

        @Override
        public void call(MAC mac) {
            String nwDstStr = IPv4.fromIPv4Address(match
                    .getNetworkDestination());
            if (null != mac) {
                log.debug("TunneledPktArpCallback.call: Mac resolved for tunneled packet to {}", nwDstStr);
                L3DevicePort devPort = devPortById
                        .get(portsAndGw.lastEgressPortId);

                if (null == devPort) {
                    log.warn("TunneledPktArpCallback.call: port {} is no longer local", portsAndGw.lastEgressPortId);
                    // TODO(pino): do we need to do anything for this?
                    // The port was removed while we waited for the ARP.
                    return;
                }

                MidoMatch newMatch = match.clone();
                // TODO(pino): get the port's mac address from the ZK config.
                newMatch.setDataLayerSource(devPort.getMacAddr());
                newMatch.setDataLayerDestination(mac);
                List<OFAction> ofActions = makeActionsForFlow(match, newMatch,
                        devPort.getNum());
                boolean useWildcards = true; // TODO: get this from config.
                if (useWildcards) {
                    // TODO: Should we check for non-load-balanced routes and
                    // wild-card flows matching them on layer 3 and lower?
                    // inPort, dlType, nwSrc.
                    match = makeWildcardedFromTunnel(match);
                }

                // If this is an ICMP error message from a peer controller,
                // don't install a flow match, just send the packet
                if (ShortUUID.UUID32toInt(portsAndGw.lastIngressPortId) == ICMP_TUNNEL) {
                    log.debug("TunneledPktArpCallback.call: forward ICMP without installing flow");
                    VRNController.super.controllerStub.sendPacketOut(
                            bufferId, (short)inPort, ofActions, data);
                } else {
                    log.debug("TunneledPktArpCallback.call: forward and install flow {}", match);
                    addFlowAndSendPacket(bufferId, match, idleFlowExpireSeconds,
                            NO_HARD_TIMEOUT, true, ofActions, (short)inPort, data);
                }
            } else {
                log.debug("TunneledPktArpCallback.call: ARP timed out for tunneled packet to {}, send ICMP",
                        nwDstStr);
                installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                        ICMP_EXPIRY_SECONDS);
                // Send an ICMP !H
                ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
                Ethernet ethPkt = new Ethernet();
                try {
                    ethPkt.deserialize(bb);
                } catch (MalformedPacketException ex) {
                    // Packet could not be deserialized: Drop it.
                    log.warn("TunneledPktArpCallback.call: dropping malformed "
                            + "packet from port {}.  {}", inPort, ex.getMessage());
                    freeBuffer(bufferId);
                    return;
                }

                //sendICMPforTunneledPkt(ICMP.UNREACH_CODE.UNREACH_HOST, ethPkt,
                //        portsAndGw.lastIngressPortId,
                //        portsAndGw.lastEgressPortId);
            }
        }
    }

    private void addFlowAndSendPacket(int bufferId, OFMatch match,
            short idleTimeoutSecs, short hardTimeoutSecs,
            boolean sendFlowRemove, List<OFAction> actions, int inPort,
            byte[] data) {
        controllerStub.sendFlowModAdd(match, 0, idleTimeoutSecs,
                hardTimeoutSecs, FLOW_PRIORITY, bufferId, sendFlowRemove,
                false, false, actions);
        // If packet was unbuffered, we need to explicitly send it otherwise the
        // flow won't be applied to it.
        if (bufferId == ControllerStub.UNBUFFERED_ID)
            controllerStub.sendPacketOut(bufferId, OFPort.OFPP_NONE.getValue(),
                    actions, data);
    }

    private class LocalPktArpCallback implements Callback<MAC> {
        public LocalPktArpCallback(int bufferId, int totalLen,
                L3DevicePort devPortIn, byte[] data, MidoMatch match,
                ForwardInfo fwdInfo, Ethernet ethPkt, Set<UUID> traversedRouters) {
            super();
            this.bufferId = bufferId;
            this.totalLen = totalLen;
            this.inPort = devPortIn;
            this.data = data;
            this.match = match;
            this.fwdInfo = fwdInfo;
            this.ethPkt = ethPkt;
            this.traversedRouters = traversedRouters;
        }

        int bufferId;
        int totalLen;
        L3DevicePort inPort;
        byte[] data;
        MidoMatch match;
        ForwardInfo fwdInfo;
        Ethernet ethPkt;
        Set<UUID> traversedRouters;

        @Override
        public void call(MAC mac) {
            String nwDstStr = IPv4.fromIPv4Address(match
                    .getNetworkDestination());
            if (null != mac) {
                log.debug("LocalPktArpCallback.call: mac resolved for local packet to {}", nwDstStr);

                L3DevicePort devPort = devPortById.get(fwdInfo.outPortId);
                if (null == devPort) {
                    log.warn("LocalPktArpCallback.call: port is no longer local");
                    freeFlowResources(match, traversedRouters);
                    freeBuffer(bufferId);
                    // TODO(pino): do we need to do anything for this?
                    // The port was removed while we waited for the ARP.
                    return;
                }
                log.debug("LocalPktArpCallback.call: forward and install flow");

                fwdInfo.matchOut.setDataLayerSource(devPort.getMacAddr());
                fwdInfo.matchOut.setDataLayerDestination(mac);
                List<OFAction> ofActions = makeActionsForFlow(match,
                        fwdInfo.matchOut, devPort.getNum());
                boolean useWildcards = false; // TODO: get this from config.
                if (useWildcards) {
                    // TODO: Should we check for non-load-balanced routes and
                    // wild-card flows matching them on layer 3 and lower?
                    // inPort, dlType, nwSrc.
                    match = makeWildcarded(match);
                }
                // Track the routers for this flow so we can free resources
                // when the flow is removed.
                matchToRouters.put(match, traversedRouters);
                addFlowAndSendPacket(bufferId, match, idleFlowExpireSeconds,
                        NO_HARD_TIMEOUT, true, ofActions, inPort.getNum(),
                        data);
            } else {
                log.debug("ARP timed out for local packet to {} - send ICMP",
                        nwDstStr);
                installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                        ICMP_EXPIRY_SECONDS);
                freeFlowResources(match, traversedRouters);
                // Send an ICMP !H
                //sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_HOST, inPort
                //        .getId(), ethPkt, fwdInfo.inPortId, fwdInfo.pktIn,
                //        fwdInfo.outPortId);
            }
            notifyFlowAdded(match, fwdInfo.matchOut, inPort.getId(), fwdInfo,
                    traversedRouters);
        }
    }

    private void sendUnbufferedPacketFromPort(Ethernet ethPkt, short portNum) {
        OFActionOutput action = new OFActionOutput(portNum, (short) 0);
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(action);
        log.debug("sendUnbufferedPacketFromPort {}", ethPkt);
        controllerStub.sendPacketOut(ControllerStub.UNBUFFERED_ID,
                OFPort.OFPP_NONE.getValue(), actions, ethPkt.serialize());
    }

    private void notifyFlowAdded(MidoMatch origMatch, MidoMatch flowMatch,
            UUID inPortId, ForwardInfo fwdInfo, Set<UUID> routers) {
        // TODO Auto-generated method stub

    }

    private void installBlackhole(MidoMatch flowMatch, int bufferId,
            short idleTimeout, short hardTimeout) {
        // TODO(pino): can we just send a null list instead of an empty list?
        List<OFAction> actions = new ArrayList<OFAction>();
        controllerStub.sendFlowModAdd(flowMatch, (long) 0, idleTimeout,
                hardTimeout, (short) 0, bufferId, true, false, false,
                actions);
        // Note that if the packet was buffered, then the datapath will apply
        // the flow and drop it. If the packet was unbuffered, we don't need
        // to do anything.
    }

    private MidoMatch makeWildcarded(MidoMatch origMatch) {
        // TODO Auto-generated method stub
        return origMatch;
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount, long matchingTunnelId) {
        log.debug("onFlowRemoved: match {} reason {}", match, reason);

        // TODO(pino): do we care why the flow was removed?
        Collection<UUID> routers = matchToRouters.get(match);
        if (null != routers) {
            log.debug("onFlowRemoved: found routers {} for match {}", routers, match);
            freeFlowResources(match, routers);
        }
    }

    public void freeFlowResources(OFMatch match, Collection<UUID> forwardingElements) {
        for (UUID feId : forwardingElements) {
            try {
                ForwardingElement fe = vrn.getForwardingElement(feId);
                fe.freeFlowResources(match);
            } catch (ZkStateSerializationException e) {
                log.warn("freeFlowResources failed for match {} in FE {} -"
                        + " caught: \n{}",
                        new Object[] { match, feId, e.getStackTrace() });
            } catch (StateAccessException e) {
                log.warn("freeFlowResources failed for match {} in FE {} -"
                        + " caught: \n{}",
                        new Object[] { match, feId, e.getStackTrace() });
            }
        }
    }

    @Override
    public void setServiceFlows(short localPortNum, short remotePortNum,
            int localAddr, int remoteAddr, short localTport, short remoteTport) {
        // Remember service's target port assuming that service flows sent
        // this packet to the OFPP_CONTROLLER.
        // TODO(yoshi): replace this with better mechanism such as ARP proxy
        // for service ports.
        serviceTargetPort = remotePortNum;

        // local to remote.
        MidoMatch match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(localAddr);
        match.setNetworkDestination(remoteAddr);
        match.setTransportDestination(remoteTport);
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        // OFPP_NONE is placed since outPort should be ignored. cf. OpenFlow
        // specification 1.0 p.15.
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
            match.setNetworkSource(localAddr);
        match.setNetworkDestination(remoteAddr);
        match.setTransportSource(localTport);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        // remote to local.
        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(remoteAddr);
        match.setNetworkDestination(localAddr);
        match.setTransportDestination(localTport);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(remoteAddr);
        match.setNetworkDestination(localAddr);
        match.setTransportSource(remoteTport);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        // ARP flows.
        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(ARP.ETHERTYPE);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(ARP.ETHERTYPE);
        // Output to both service port and controller port. Output to
        // OFPP_CONTROLLER requires to set non-zero value to max_len, and we
        // are setting the standard max_len (128 bytes) in OpenFlow.
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        actions.add(new OFActionOutput(OFPort.OFPP_CONTROLLER.getValue(),
                (short) 128));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        // ICMP flows.
        // Only valid for the service port with specified address.
        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setNetworkSource(localAddr);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setNetworkDestination(localAddr);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);
    }

    private void startPortService(final short portNum, final UUID portId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException, IOException, StateAccessException {
        // If the materiazlied router port isn't discovered yet, try
        // setting flows between BGP peers later.
        if (devPortById.containsKey(portId)) {
            service.start(datapathId, portNum,
                          devPortById.get(portId).getNum());
        } else {
            if (!portServicesById.containsKey(portId)) {
                portServicesById.put(portId, new ArrayList<Runnable>());
            }
            List<Runnable> watchers = portServicesById.get(portId);
            watchers.add(new Runnable() {
                public void run() {
                    try {
                        service.start(datapathId, portNum,
                                      devPortById.get(portId).getNum());
                    } catch (Exception e) {
                        log.warn("startPortService", e);
                    }
                }
            });
        }
    }

    private void setupServicePort(int portNum, String portName)
            throws StateAccessException, ZkStateSerializationException,
            IOException, KeeperException, InterruptedException {
        UUID portId = service.getRemotePort(portName);
        if (portId != null) {
            service.configurePort(portId, portName);
            startPortService((short)portNum, portId);
        }
    }

    private void addServicePort(L3DevicePort port) throws StateAccessException,
            ZkStateSerializationException, KeeperException {
        Set<String> servicePorts = service.getPorts(port.getId());
        if (!servicePorts.isEmpty()) {
            UUID portId = port.getId();
            if (portServicesById.containsKey(portId)) {
                for (Runnable watcher : portServicesById.get(portId)) {
                    watcher.run();
                }
                return;
            }
        }
        service.addPort(datapathId, port.getId(), port.getMacAddr());
    }

    @Override
    protected void portMoved(UUID portUuid, IntIPv4 oldAddr, IntIPv4 newAddr) {
        // Do nothing.
    }

    @Override
    public final void clear() {
        // Do nothing.
    }

    @Override
    protected void addVirtualPort(int portNum, String name, MAC addr,
            UUID portId) {
        L3DevicePort devPort = devPortByNum.get(portNum);
        if (null != devPort) {
            log.error("addVirtualPort num:{} name:{} was already added.",
                    portNum, name);
            return;
        }

        try {
            devPort = new L3DevicePort(portMgr, routeMgr, portId,
                    (short)portNum, addr, super.controllerStub);
        } catch (Exception e) {
            log.error("addVirtualPort", e);
            return;
        }
        devPortById.put(portId, devPort);
        devPortByNum.put(portNum, devPort);

        log.info("addVirtualPort number {} bound to vport {} with "
                + "nw address {}", new Object[] { portNum, devPort.getId(),
                IPv4.fromIPv4Address(devPort.getVirtualConfig().portAddr) });
        try {
            vrn.addPort(portId);
            addServicePort(devPort);
        } catch (Exception e) {
            log.error("addVirtualPort", e);
        }
        setFlowsForHandlingDhcpInController(devPort.getNum());
    }

    @Override
    protected void deleteVirtualPort(int portNum, UUID portId) {
        L3DevicePort devPort = devPortByNum.get(portNum);
        if (null == devPort) {
            log.error("deleteVirtualPort num:{} uuid:{} was never added.",
                    portNum, portId);
            return;
        }
        // TODO(pino): should we check that the devPort's uuid == portId?
        log.info("deletePort number {} bound to virtual port {} with "
                + "nw address {}", new Object[] { devPort.getNum(),
                portId, devPort.getVirtualConfig().portAddr });
        try {
            vrn.removePort(portId);
        } catch (Exception e) {
            log.error("deleteVirtualPort", e);
        }
        devPortById.remove(portId);
        devPortByNum.remove(portNum);
    }

    @Override
    protected void addServicePort(int num, String name, UUID vId) {
        try {
            setupServicePort(num, name);
        } catch (Exception e) {
            log.error("addServicePort", e);
        }
    }

    @Override
    protected void deleteServicePort(int num, String name, UUID vId) {
        // TODO: handle the removal of a service port.
    }

    @Override
    protected void addTunnelPort(int num, IntIPv4 peerIP) {
        // Do nothing.
    }

    @Override
    protected void deleteTunnelPort(int num, IntIPv4 peerIP) {
        // Do nothing.
    }

}
