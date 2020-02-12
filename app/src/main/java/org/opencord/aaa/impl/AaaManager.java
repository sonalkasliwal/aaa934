/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencord.aaa.impl;

import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;
import static org.slf4j.LoggerFactory.getLogger;

import com.google.common.collect.Sets;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.packet.DeserializationException;
import org.onlab.packet.EAP;
import org.onlab.packet.EAPOL;
import org.onlab.packet.EthType;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.RADIUS;
import org.onlab.packet.RADIUSAttribute;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.AbstractListenerManager;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.opencord.aaa.AaaConfig;
import org.opencord.aaa.AaaMachineStatisticsEvent;
import org.opencord.aaa.AaaMachineStatisticsService;
import org.opencord.aaa.AaaSupplicantMachineStats;
import org.opencord.aaa.AuthenticationEvent;
import org.opencord.aaa.AuthenticationEventListener;
import org.opencord.aaa.AuthenticationService;
import org.opencord.aaa.AuthenticationStatisticsEvent;
import org.opencord.aaa.AuthenticationStatisticsService;
import org.opencord.aaa.RadiusCommunicator;
import org.opencord.aaa.RadiusOperationalStatusEvent;
import org.opencord.aaa.RadiusOperationalStatusService;
import org.opencord.aaa.StateMachineDelegate;
import org.opencord.aaa.RadiusOperationalStatusService.RadiusOperationalStatusEvaluationMode;
import org.opencord.sadis.BaseInformationService;
import org.opencord.sadis.SadisService;
import org.opencord.sadis.SubscriberAndDeviceInformation;
import org.osgi.service.component.ComponentContext;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Activate;
import org.slf4j.Logger;
import com.google.common.base.Strings;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
/**
 * AAA application for ONOS.
 */
@Service
@Component(immediate = true)
public class AaaManager
        extends AbstractListenerManager<AuthenticationEvent, AuthenticationEventListener>
        implements AuthenticationService {

    private static final String APP_NAME = "org.opencord.aaa";

    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry netCfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected SadisService sadisService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected AuthenticationStatisticsService aaaStatisticsManager;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected AaaMachineStatisticsService aaaSupplicantStatsManager;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected RadiusOperationalStatusService radiusOperationalStatusService;

    protected AuthenticationStatisticsEventPublisher authenticationStatisticsPublisher;
    protected BaseInformationService<SubscriberAndDeviceInformation> subsService;
    protected final DeviceListener deviceListener = new InternalDeviceListener();

    private static final int DEFAULT_REPEAT_DELAY = 20;
    @Property(name = "statisticsGenerationPeriodInSeconds", intValue = DEFAULT_REPEAT_DELAY,
              label = "AAA Statistics generation frequency in seconds")
    private int statisticsGenerationPeriodInSeconds = DEFAULT_REPEAT_DELAY;

    private static final int DEFAULT_OPERATIONAL_STATUS_SERVER_EVENT_GENERATION = 30;
    @Property(name = "operationalStatusEventGenerationPeriodInSeconds",
            intValue = DEFAULT_OPERATIONAL_STATUS_SERVER_EVENT_GENERATION, label = "AAA Radius Server Operational "
                    + "Status Frequency in seconds")
    private int operationalStatusEventGenerationPeriodInSeconds = DEFAULT_OPERATIONAL_STATUS_SERVER_EVENT_GENERATION;

    private static final int DEFAULT_OPERATIONAL_STATUS_SERVER_TIMEOUT = 10;
    @Property(name = "operationalStatusServerTimeoutInSeconds",
            intValue = DEFAULT_OPERATIONAL_STATUS_SERVER_TIMEOUT, label = "Maximum period(in Seconds) to "
                    + "wait for Status response from AAA Server ")
    private int operationalStatusServerTimeoutInSeconds = DEFAULT_OPERATIONAL_STATUS_SERVER_TIMEOUT;

    private static final String DEFAULT_STATUS_SERVER_MODE = "AUTO";
    @Property(name = "operationalStatusEvaluationMode", value = DEFAULT_STATUS_SERVER_MODE,
            label = "Evaluation mode for determining the Operational Status of Radius Server. Valid values are AUTO "
                    + "(default), STATUS_REQUEST and ACCESS_REQUEST")
    protected String operationalStatusEvaluationMode = DEFAULT_STATUS_SERVER_MODE;

    // NAS IP address
    protected InetAddress nasIpAddress;

    // self MAC address
    protected String nasMacAddress;

    // Parsed RADIUS server addresses
    protected InetAddress radiusIpAddress;

    // MAC address of RADIUS server or net hop router
    protected String radiusMacAddress;

    // RADIUS server secret
    protected String radiusSecret;

    // bindings
    protected CustomizationInfo customInfo;

    // our application-specific event handler
    private ReactivePacketProcessor processor = new ReactivePacketProcessor();

    // our unique identifier
    private ApplicationId appId;

    // TimeOut time for cleaning up stateMachines stuck due to pending AAA/EAPOL message.
    protected int cleanupTimerTimeOutInMins;

    // Setup specific customization/attributes on the RADIUS packets
    PacketCustomizer pktCustomizer;

    // packet customizer to use
    private String customizer;

    // Type of connection to use to communicate with Radius server, options are
    // "socket" or "packet_out"
    private String radiusConnectionType;

    // Object for the specific type of communication with the RADIUS
    // server, socket based or packet_out based
    RadiusCommunicator impl = null;

    // latest configuration
    AaaConfig newCfg;

    // AaaSuplicantMachineStats Object;
    AaaSupplicantMachineStats aaaSupplicantObj;

    ScheduledFuture<?> scheduledFuture;
    ScheduledFuture<?> scheduledStatusServerChecker;
    ScheduledExecutorService executor;
    String configuredAaaServerAddress;
    HashSet<Byte> outPacketSet = new HashSet<Byte>();
    HashSet<Byte> outPacketSupp = new HashSet<Byte>();
    static final List<Byte> VALID_EAPOL_TYPE = Arrays.asList(EAPOL.EAPOL_START, EAPOL.EAPOL_LOGOFF, EAPOL.EAPOL_PACKET);
    static final int HEADER_LENGTH = 4;
    // Configuration properties factory
    private final ConfigFactory factory =
            new ConfigFactory<ApplicationId, AaaConfig>(APP_SUBJECT_FACTORY,
                                                         AaaConfig.class,
                                                         "AAA") {
                @Override
                public AaaConfig createConfig() {
                    return new AaaConfig();
                }
            };

    // Listener for config changes
    private final InternalConfigListener cfgListener = new InternalConfigListener();

    private StateMachineDelegate delegate = new InternalStateMachineDelegate();

    /**
     * Builds an EAPOL packet based on the given parameters.
     *
     * @param dstMac    destination MAC address
     * @param srcMac    source MAC address
     * @param vlan      vlan identifier
     * @param eapolType EAPOL type
     * @param eap       EAP payload
     * @return Ethernet frame
     */
    private static Ethernet buildEapolResponse(MacAddress dstMac, MacAddress srcMac,
                                               short vlan, byte eapolType, EAP eap, byte priorityCode) {

        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(dstMac.toBytes());
        eth.setSourceMACAddress(srcMac.toBytes());
        eth.setEtherType(EthType.EtherType.EAPOL.ethType().toShort());
        if (vlan != Ethernet.VLAN_UNTAGGED) {
            eth.setVlanID(vlan);
            eth.setPriorityCode(priorityCode);
        }
        //eapol header
        EAPOL eapol = new EAPOL();
        eapol.setEapolType(eapolType);
        eapol.setPacketLength(eap.getLength());

        //eap part
        eapol.setPayload(eap);

        eth.setPayload(eapol);
        eth.setPad(true);
        return eth;
    }

    @Activate
    public void activate(ComponentContext context) {
        appId = coreService.registerApplication(APP_NAME);
        eventDispatcher.addSink(AuthenticationEvent.class, listenerRegistry);
        netCfgService.addListener(cfgListener);
        netCfgService.registerConfigFactory(factory);
        cfgService.registerProperties(getClass());
        modified(context);
        subsService = sadisService.getSubscriberInfoService();
        customInfo = new CustomizationInfo(subsService, deviceService);
        cfgListener.reconfigureNetwork(netCfgService.getConfig(appId, AaaConfig.class));
        log.info("Starting with config {} {}", this, newCfg);
        configureRadiusCommunication();
        // register our event handler
        packetService.addProcessor(processor, PacketProcessor.director(2));
        StateMachine.initializeMaps();
        StateMachine.setDelegate(delegate);
        cleanupTimerTimeOutInMins = newCfg.sessionCleanupTimer();
        StateMachine.setcleanupTimerTimeOutInMins(cleanupTimerTimeOutInMins);
        impl.initializeLocalState(newCfg);
        impl.requestIntercepts();
        deviceService.addListener(deviceListener);
        getConfiguredAaaServerAddress();
        radiusOperationalStatusService.initialize(nasIpAddress.getAddress(), radiusSecret, impl);
        authenticationStatisticsPublisher =
                new AuthenticationStatisticsEventPublisher();
        executor = Executors.newScheduledThreadPool(3);

        scheduledFuture = executor.scheduleAtFixedRate(authenticationStatisticsPublisher,
            0, statisticsGenerationPeriodInSeconds, TimeUnit.SECONDS);
        scheduledStatusServerChecker = executor.scheduleAtFixedRate(new ServerStatusChecker(), 0,
            operationalStatusEventGenerationPeriodInSeconds, TimeUnit.SECONDS);

        log.info("Started");
    }

    @Deactivate
    public void deactivate(ComponentContext context) {
        impl.withdrawIntercepts();
        packetService.removeProcessor(processor);
        netCfgService.removeListener(cfgListener);
        cfgService.unregisterProperties(getClass(), false);
        StateMachine.unsetDelegate(delegate);
        StateMachine.destroyMaps();
        impl.deactivate();
        deviceService.removeListener(deviceListener);
        eventDispatcher.removeSink(AuthenticationEvent.class);
        scheduledFuture.cancel(true);
        scheduledStatusServerChecker.cancel(true);
        executor.shutdown();
        log.info("Stopped");
    }
    @Modified
    public void modified(ComponentContext context) {
        Dictionary<String, Object> properties = context.getProperties();
        String s = Tools.get(properties, "statisticsGenerationPeriodInSeconds");
        statisticsGenerationPeriodInSeconds = Strings.isNullOrEmpty(s) ? DEFAULT_REPEAT_DELAY
                : Integer.parseInt(s.trim());

        s = Tools.get(properties, "operationalStatusEventGenerationPeriodInSeconds");
        operationalStatusEventGenerationPeriodInSeconds = Strings.isNullOrEmpty(s)
                ? DEFAULT_OPERATIONAL_STATUS_SERVER_EVENT_GENERATION
                    : Integer.parseInt(s.trim());

        s = Tools.get(properties, "operationalStatusServerTimeoutInSeconds");
        operationalStatusServerTimeoutInSeconds = Strings.isNullOrEmpty(s) ? DEFAULT_OPERATIONAL_STATUS_SERVER_TIMEOUT
                : Integer.parseInt(s.trim());

        s = Tools.get(properties, "operationalStatusEvaluationMode");
        String newEvaluationModeString = Strings.isNullOrEmpty(s) ? DEFAULT_STATUS_SERVER_MODE : s.trim();

        radiusOperationalStatusService
            .setOperationalStatusServerTimeoutInMillis(operationalStatusServerTimeoutInSeconds * 1000);
        RadiusOperationalStatusEvaluationMode newEvaluationMode =
                RadiusOperationalStatusEvaluationMode.getValue(newEvaluationModeString);
        if (newEvaluationMode != null) {
            radiusOperationalStatusService.setRadiusOperationalStatusEvaluationMode(newEvaluationMode);
            operationalStatusEvaluationMode = newEvaluationModeString;
        } else {
            properties.put("operationalStatusEvaluationMode", operationalStatusEvaluationMode);
        }
    }

    protected void configureRadiusCommunication() {
        if (radiusConnectionType.toLowerCase().equals("socket")) {
            impl = new SocketBasedRadiusCommunicator(appId, packetService, this);
        } else {
            impl = new PortBasedRadiusCommunicator(appId, packetService, mastershipService,
                    deviceService, subsService, pktCustomizer, this);
        }
    }

    private void configurePacketCustomizer() {
        switch (customizer.toLowerCase()) {
            case "sample":
                pktCustomizer = new SamplePacketCustomizer(customInfo);
                log.info("Created SamplePacketCustomizer");
                break;
            case "att":
                pktCustomizer = new AttPacketCustomizer(customInfo);
                log.info("Created AttPacketCustomizer");
                break;
            default:
                pktCustomizer = new PacketCustomizer(customInfo);
                log.info("Created default PacketCustomizer");
                break;
        }
    }

    private void getConfiguredAaaServerAddress() {
        try {
            InetAddress address;
            if (newCfg.radiusHostName() != null) {
                address = InetAddress.getByName(newCfg.radiusHostName());
            } else {
                 address = newCfg.radiusIp();
            }

            configuredAaaServerAddress = address.getHostAddress();
        } catch (UnknownHostException uhe) {
            log.warn("Unable to resolve host {}", newCfg.radiusHostName());
        }
    }

    private void checkReceivedPacketForValidValidator(RADIUS radiusPacket, byte[] requestAuthenticator) {
        if (!checkResponseMessageAuthenticator(radiusSecret, radiusPacket, requestAuthenticator)) {
            aaaStatisticsManager.getAaaStats().increaseInvalidValidatorsRx();
        }
    }

    private boolean checkResponseMessageAuthenticator(String key, RADIUS radiusPacket, byte[] requestAuthenticator) {
        byte[] newHash = new byte[16];
        Arrays.fill(newHash, (byte) 0);
        byte[] messageAuthenticator = radiusPacket.getAttribute(RADIUSAttribute.RADIUS_ATTR_MESSAGE_AUTH).getValue();
        byte[] authenticator = radiusPacket.getAuthenticator();
        radiusPacket.updateAttribute(RADIUSAttribute.RADIUS_ATTR_MESSAGE_AUTH, newHash);
        radiusPacket.setAuthenticator(requestAuthenticator);
        // Calculate the MD5 HMAC based on the message
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "HmacMD5");
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(keySpec);
            newHash = mac.doFinal(radiusPacket.serialize());
        } catch (Exception e) {
            log.error("Failed to generate message authenticator: {}", e.getMessage());
        }
        radiusPacket.updateAttribute(RADIUSAttribute.RADIUS_ATTR_MESSAGE_AUTH, messageAuthenticator);
        radiusPacket.setAuthenticator(authenticator);
        // Compare the calculated Message-Authenticator with the one in the message
        return Arrays.equals(newHash, messageAuthenticator);
    }
    public void checkForPacketFromUnknownServer(String hostAddress) {
            if (!hostAddress.equals(configuredAaaServerAddress)) {
                 getConfiguredAaaServerAddress();
                 if (!hostAddress.equals(configuredAaaServerAddress)) {
                     aaaStatisticsManager.getAaaStats().incrementUnknownServerRx();
                 }
            }
    }

    /**
     * Send RADIUS packet to the RADIUS server.
     *
     * @param radiusPacket RADIUS packet to be sent to server.
     * @param inPkt        Incoming EAPOL packet
     */
    protected void sendRadiusPacket(RADIUS radiusPacket, InboundPacket inPkt) {
        outPacketSet.add(radiusPacket.getIdentifier());
        aaaStatisticsManager.getAaaStats().increaseOrDecreasePendingRequests(true);
        aaaStatisticsManager.getAaaStats().increaseAccessRequestsTx();
        aaaStatisticsManager.putOutgoingIdentifierToMap(radiusPacket.getIdentifier());
        impl.sendRadiusPacket(radiusPacket, inPkt);
    }

   /**
     * For scheduling the timer required for cleaning up StateMachine
     * when no response
     * from RADIUS SERVER.
     *
     * @param sessionId    SessionId of the current session
     * @param stateMachine StateMachine for the id
     */
    public void scheduleStateMachineCleanupTimer(String sessionId, StateMachine stateMachine) {
        StateMachine.CleanupTimerTask cleanupTask = stateMachine.new CleanupTimerTask(sessionId, this);
        ScheduledFuture<?> cleanupTimer = executor.schedule(cleanupTask, cleanupTimerTimeOutInMins, TimeUnit.MINUTES);
        stateMachine.setCleanupTimer(cleanupTimer);

    }

   /**
     * Handles RADIUS packets.
     *
     * @param radiusPacket RADIUS packet coming from the RADIUS server.
     * @throws StateMachineException if an illegal state transition is triggered
     * @throws DeserializationException if packet deserialization fails
     */
    public void handleRadiusPacket(RADIUS radiusPacket)
            throws StateMachineException, DeserializationException {
        if (log.isTraceEnabled()) {
            log.trace("Received RADIUS packet {}", radiusPacket);
        }
        if (radiusOperationalStatusService.isRadiusResponseForOperationalStatus(radiusPacket.getIdentifier())) {
            radiusOperationalStatusService.handleRadiusPacketForOperationalStatus(radiusPacket);
            return;
        }
        StateMachine stateMachine = StateMachine.lookupStateMachineById(radiusPacket.getIdentifier());
        if (stateMachine == null) {
            log.error("Invalid packet identifier {}, could not find corresponding "
                    + "state machine ... exiting", radiusPacket.getIdentifier());
            aaaStatisticsManager.getAaaStats().incrementNumberOfSessionsExpired();
            aaaStatisticsManager.getAaaStats().countDroppedResponsesRx();
            return;
        }

        //instance of StateMachine using the sessionId for updating machine stats
        StateMachine machineStats = StateMachine.lookupStateMachineBySessionId(stateMachine.sessionId());

        EAP eapPayload;
        Ethernet eth;
        checkReceivedPacketForValidValidator(radiusPacket, stateMachine.requestAuthenticator());

        //increasing packets and octets received from server
        machineStats.incrementTotalPacketsReceived();
        machineStats.incrementTotalOctetReceived(radiusPacket.decapsulateMessage().getLength());

        if (outPacketSet.contains(radiusPacket.getIdentifier())) {
            aaaStatisticsManager.getAaaStats().increaseOrDecreasePendingRequests(false);
            outPacketSet.remove(new Byte(radiusPacket.getIdentifier()));
        }
        switch (radiusPacket.getCode()) {
            case RADIUS.RADIUS_CODE_ACCESS_CHALLENGE:
                log.debug("RADIUS packet: RADIUS_CODE_ACCESS_CHALLENGE");
                RADIUSAttribute radiusAttrState = radiusPacket.getAttribute(RADIUSAttribute.RADIUS_ATTR_STATE);
                byte[] challengeState = null;
                if (radiusAttrState != null) {
                    challengeState = radiusAttrState.getValue();
                }
                eapPayload = radiusPacket.decapsulateMessage();
                stateMachine.setChallengeInfo(eapPayload.getIdentifier(), challengeState);
                eth = buildEapolResponse(stateMachine.supplicantAddress(),
                        MacAddress.valueOf(nasMacAddress),
                        stateMachine.vlanId(),
                        EAPOL.EAPOL_PACKET,
                        eapPayload, stateMachine.priorityCode());
                log.debug("Send EAP challenge response to supplicant {}", stateMachine.supplicantAddress().toString());
                sendPacketToSupplicant(eth, stateMachine.supplicantConnectpoint(), true);
                aaaStatisticsManager.getAaaStats().increaseChallengeResponsesRx();
                outPacketSupp.add(eapPayload.getIdentifier());
                aaaStatisticsManager.getAaaStats().incrementPendingResSupp();
                //increasing packets send to server
                machineStats.incrementTotalPacketsSent();
                machineStats.incrementTotalOctetSent(eapPayload.getLength());
                break;
            case RADIUS.RADIUS_CODE_ACCESS_ACCEPT:
                log.debug("RADIUS packet: RADIUS_CODE_ACCESS_ACCEPT");
                //send an EAPOL - Success to the supplicant.
                byte[] eapMessageSuccess =
                        radiusPacket.getAttribute(RADIUSAttribute.RADIUS_ATTR_EAP_MESSAGE).getValue();
                eapPayload = EAP.deserializer().deserialize(
                        eapMessageSuccess, 0, eapMessageSuccess.length);
                eth = buildEapolResponse(stateMachine.supplicantAddress(),
                        MacAddress.valueOf(nasMacAddress),
                        stateMachine.vlanId(),
                        EAPOL.EAPOL_PACKET,
                        eapPayload, stateMachine.priorityCode());
                log.info("Send EAP success message to supplicant {}", stateMachine.supplicantAddress().toString());
                sendPacketToSupplicant(eth, stateMachine.supplicantConnectpoint(), false);
                aaaStatisticsManager.getAaaStats().incrementEapolAuthSuccessTrans();

                stateMachine.authorizeAccess();
                aaaStatisticsManager.getAaaStats().increaseAcceptResponsesRx();
                //increasing packets send to server
                machineStats.incrementTotalPacketsSent();
                machineStats.incrementTotalOctetSent(eapPayload.getLength());
                break;
            case RADIUS.RADIUS_CODE_ACCESS_REJECT:
                log.debug("RADIUS packet: RADIUS_CODE_ACCESS_REJECT");
                //send an EAPOL - Failure to the supplicant.
                byte[] eapMessageFailure;
                eapPayload = new EAP();
                RADIUSAttribute radiusAttrEap = radiusPacket.getAttribute(RADIUSAttribute.RADIUS_ATTR_EAP_MESSAGE);
                if (radiusAttrEap == null) {
                    eapPayload.setCode(EAP.FAILURE);
                    eapPayload.setIdentifier(stateMachine.challengeIdentifier());
                    eapPayload.setLength(EAP.EAP_HDR_LEN_SUC_FAIL);
                } else {
                    eapMessageFailure = radiusAttrEap.getValue();
                    eapPayload = EAP.deserializer().deserialize(
                            eapMessageFailure, 0, eapMessageFailure.length);
                }
                eth = buildEapolResponse(stateMachine.supplicantAddress(),
                        MacAddress.valueOf(nasMacAddress),
                        stateMachine.vlanId(),
                        EAPOL.EAPOL_PACKET,
                        eapPayload, stateMachine.priorityCode());
                log.warn("Send EAP failure message to supplicant {}", stateMachine.supplicantAddress().toString());
                sendPacketToSupplicant(eth, stateMachine.supplicantConnectpoint(), false);
                aaaStatisticsManager.getAaaStats().incrementEapolauthFailureTrans();

                stateMachine.denyAccess();
                aaaStatisticsManager.getAaaStats().increaseRejectResponsesRx();
                //increasing packets send to server
                machineStats.incrementTotalPacketsSent();
                machineStats.incrementTotalOctetSent(eapPayload.getLength());
                //pushing machine stats to kafka
                aaaSupplicantObj = aaaSupplicantStatsManager.getSupplicantStats(machineStats);
                aaaSupplicantStatsManager.getMachineStatsDelegate()
                        .notify(new AaaMachineStatisticsEvent(
                                AaaMachineStatisticsEvent.Type.STATS_UPDATE, aaaSupplicantObj));
                break;
            default:
                log.warn("Unknown RADIUS message received with code: {}", radiusPacket.getCode());
                aaaStatisticsManager.getAaaStats().increaseUnknownTypeRx();
                //increasing packets received to server
                machineStats.incrementTotalPacketsReceived();
                machineStats.incrementTotalOctetReceived(radiusPacket.decapsulateMessage().getLength());
        }
        aaaStatisticsManager.getAaaStats().countDroppedResponsesRx();
    }

    /**
     * Send the ethernet packet to the supplicant.
     *
     * @param ethernetPkt  the ethernet packet
     * @param connectPoint the connect point to send out
     */
    private void sendPacketToSupplicant(Ethernet ethernetPkt, ConnectPoint connectPoint, boolean isChallengeResponse) {
        TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(connectPoint.port()).build();
        OutboundPacket packet = new DefaultOutboundPacket(connectPoint.deviceId(),
                                                          treatment, ByteBuffer.wrap(ethernetPkt.serialize()));
        EAPOL eap = ((EAPOL) ethernetPkt.getPayload());
        EAP eapPkt = (EAP) eap.getPayload();
        if (log.isTraceEnabled()) {
            log.trace("Sending eapol payload {} enclosed in {} to supplicant at {}",
                      eap, ethernetPkt, connectPoint);
        }
        packetService.emit(packet);
        if (isChallengeResponse) {
            aaaStatisticsManager.getAaaStats().incrementEapPktTxauthEap();
        }
        aaaStatisticsManager.getAaaStats().incrementEapolFramesTx();
        aaaStatisticsManager.getAaaStats().countReqEapFramesTx();
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this);
    }

    // our handler defined as a private inner class

    /**
     * Packet processor responsible for forwarding packets along their paths.
     */
    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {

            // Extract the original Ethernet frame from the packet information
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if (ethPkt == null) {
                return;
            }

            try {
                // identify if incoming packet comes from supplicant (EAP) or RADIUS
                switch (EthType.EtherType.lookup(ethPkt.getEtherType())) {
                    case EAPOL:
                        handleSupplicantPacket(context.inPacket());
                        break;
                    default:
                        // any other packets let the specific implementation handle
                        impl.handlePacketFromServer(context);
                }
            } catch (StateMachineException e) {
                log.warn("Unable to process packet:", e);
            }
        }

        /**
         * Creates and initializes common fields of a RADIUS packet.
         *
         * @param stateMachine state machine for the request
         * @param eapPacket  EAP packet
         * @return RADIUS packet
         */
        private RADIUS getRadiusPayload(StateMachine stateMachine, byte identifier, EAP eapPacket) {
            RADIUS radiusPayload =
                    new RADIUS(RADIUS.RADIUS_CODE_ACCESS_REQUEST,
                               eapPacket.getIdentifier());

            // set Request Authenticator in StateMachine
            stateMachine.setRequestAuthenticator(radiusPayload.generateAuthCode());

            radiusPayload.setIdentifier(identifier);
            radiusPayload.setAttribute(RADIUSAttribute.RADIUS_ATTR_USERNAME,
                                       stateMachine.username());

            radiusPayload.setAttribute(RADIUSAttribute.RADIUS_ATTR_NAS_IP,
                    AaaManager.this.nasIpAddress.getAddress());

            radiusPayload.encapsulateMessage(eapPacket);

            return radiusPayload;
        }

        /**
         * Handles PAE packets (supplicant).
         *
         * @param inPacket Ethernet packet coming from the supplicant
         */
        private void handleSupplicantPacket(InboundPacket inPacket) throws StateMachineException {
            Ethernet ethPkt = inPacket.parsed();
            // Where does it come from?
            MacAddress srcMac = ethPkt.getSourceMAC();

            DeviceId deviceId = inPacket.receivedFrom().deviceId();
            PortNumber portNumber = inPacket.receivedFrom().port();
            String sessionId = deviceId.toString() + portNumber.toString();
            EAPOL eapol = (EAPOL) ethPkt.getPayload();
            if (log.isTraceEnabled()) {
                log.trace("Received EAPOL packet {} in enclosing packet {} from "
                        + "dev/port: {}/{}", eapol, ethPkt, deviceId,
                          portNumber);
            }

            short pktlen = eapol.getPacketLength();
            byte[] eapPayLoadBuffer = eapol.serialize();
            int len = eapPayLoadBuffer.length;
            if (len != (HEADER_LENGTH + pktlen)) {
                aaaStatisticsManager.getAaaStats().incrementInvalidBodyLength();
                return;
            }
            if (!VALID_EAPOL_TYPE.contains(eapol.getEapolType())) {
                aaaStatisticsManager.getAaaStats().incrementInvalidPktType();
                return;
            }
            if (pktlen >= 0 && ethPkt.getEtherType() == EthType.EtherType.EAPOL.ethType().toShort()) {
                aaaStatisticsManager.getAaaStats().incrementValidEapolFramesRx();
            }
            StateMachine stateMachine = StateMachine.lookupStateMachineBySessionId(sessionId);
            if (stateMachine == null) {
                log.debug("Creating new state machine for sessionId: {} for "
                                + "dev/port: {}/{}", sessionId, deviceId, portNumber);
                stateMachine = new StateMachine(sessionId);
            } else {
                log.debug("Using existing state-machine for sessionId: {}", sessionId);
                stateMachine.setEapolTypeVal(eapol.getEapolType());
            }

            switch (eapol.getEapolType()) {
                case EAPOL.EAPOL_START:
                    log.debug("EAP packet: EAPOL_START");
                    stateMachine.setSupplicantConnectpoint(inPacket.receivedFrom());
                    if (stateMachine.getCleanupTimer() == null) {
                        scheduleStateMachineCleanupTimer(sessionId, stateMachine);
                    }
                    stateMachine.start();
                    aaaStatisticsManager.getAaaStats().incrementEapolStartReqTrans();
                    //send an EAP Request/Identify to the supplicant
                    EAP eapPayload = new EAP(EAP.REQUEST, stateMachine.identifier(), EAP.ATTR_IDENTITY, null);
                    if (ethPkt.getVlanID() != Ethernet.VLAN_UNTAGGED) {
                       stateMachine.setPriorityCode(ethPkt.getPriorityCode());
                    }
                    Ethernet eth = buildEapolResponse(srcMac, MacAddress.valueOf(nasMacAddress),
                                                      ethPkt.getVlanID(), EAPOL.EAPOL_PACKET,
                                                      eapPayload, stateMachine.priorityCode());

                    stateMachine.setSupplicantAddress(srcMac);
                    stateMachine.setVlanId(ethPkt.getVlanID());
                    log.debug("Getting EAP identity from supplicant {}", stateMachine.supplicantAddress().toString());
                    sendPacketToSupplicant(eth, stateMachine.supplicantConnectpoint(), false);
                    aaaStatisticsManager.getAaaStats().incrementRequestIdFramesTx();

                    break;
                case EAPOL.EAPOL_LOGOFF:
                    log.debug("EAP packet: EAPOL_LOGOFF");
                    //posting the machine stat data for current supplicant device.
                    if (stateMachine.getSessionTerminateReason() == null ||
                            stateMachine.getSessionTerminateReason().equals("")) {
                        stateMachine.setSessionTerminateReason(
                                StateMachine.SessionTerminationReasons.SUPPLICANT_LOGOFF.getReason());
                    }
                    aaaSupplicantObj = aaaSupplicantStatsManager.getSupplicantStats(stateMachine);
                    aaaSupplicantStatsManager.getMachineStatsDelegate()
                            .notify(new AaaMachineStatisticsEvent(
                                    AaaMachineStatisticsEvent.Type.STATS_UPDATE, aaaSupplicantObj));
                    if (stateMachine.state() == StateMachine.STATE_AUTHORIZED) {
                        stateMachine.logoff();
                        aaaStatisticsManager.getAaaStats().incrementEapolLogoffRx();
                    }
                    if (stateMachine.state() == StateMachine.STATE_IDLE) {
                        aaaStatisticsManager.getAaaStats().incrementAuthStateIdle();
                    }

                    break;
                case EAPOL.EAPOL_PACKET:
                    RADIUS radiusPayload;
                    // check if this is a Response/Identify or  a Response/TLS
                    EAP eapPacket = (EAP) eapol.getPayload();
                    Byte identifier = new Byte(eapPacket.getIdentifier());

                    byte dataType = eapPacket.getDataType();
                    switch (dataType) {

                        case EAP.ATTR_IDENTITY:
                            log.debug("EAP packet: EAPOL_PACKET ATTR_IDENTITY");
                            //Setting the time of this response from RG, only when its not a re-transmission.
                            if (stateMachine.getLastPacketReceivedTime() == 0) {
                               stateMachine.setLastPacketReceivedTime(System.currentTimeMillis());
                            }
                            // request id access to RADIUS
                            stateMachine.setUsername(eapPacket.getData());

                            radiusPayload = getRadiusPayload(stateMachine, stateMachine.identifier(), eapPacket);
                            radiusPayload = pktCustomizer.customizePacket(radiusPayload, inPacket);
                            radiusPayload.addMessageAuthenticator(AaaManager.this.radiusSecret);

                            sendRadiusPacket(radiusPayload, inPacket);
                            stateMachine.setWaitingForRadiusResponse(true);
                            aaaStatisticsManager.getAaaStats().incrementEapolAtrrIdentity();
                            // change the state to "PENDING"
                            if (stateMachine.state() == StateMachine.STATE_PENDING) {
                                aaaStatisticsManager.getAaaStats().increaseRequestReTx();
                                stateMachine.incrementTotalPacketsSent();
                                stateMachine.incrementTotalOctetSent(eapol.getPacketLength());
                            }
                            stateMachine.requestAccess();
                            break;
                        case EAP.ATTR_MD5:
                            stateMachine.setLastPacketReceivedTime(System.currentTimeMillis());
                            log.debug("EAP packet: EAPOL_PACKET ATTR_MD5");
                            // verify if the EAP identifier corresponds to the
                            // challenge identifier from the client state
                            // machine.
                            if (eapPacket.getIdentifier() == stateMachine.challengeIdentifier()) {
                                //send the RADIUS challenge response
                                radiusPayload =
                                        getRadiusPayload(stateMachine,
                                                         stateMachine.identifier(),
                                                         eapPacket);
                                radiusPayload = pktCustomizer.customizePacket(radiusPayload, inPacket);

                                if (stateMachine.challengeState() != null) {
                                    radiusPayload.setAttribute(RADIUSAttribute.RADIUS_ATTR_STATE,
                                            stateMachine.challengeState());
                                }
                                radiusPayload.addMessageAuthenticator(AaaManager.this.radiusSecret);
                                if (outPacketSupp.contains(eapPacket.getIdentifier())) {
                                    aaaStatisticsManager.getAaaStats().decrementPendingResSupp();
                                    outPacketSupp.remove(identifier);
                                }
                                sendRadiusPacket(radiusPayload, inPacket);
                                stateMachine.setWaitingForRadiusResponse(true);
                                aaaStatisticsManager.getAaaStats().incrementEapolMd5RspChall();
                            }
                            break;
                        case EAP.ATTR_TLS:
                            log.debug("EAP packet: EAPOL_PACKET ATTR_TLS");
                            // request id access to RADIUS
                            radiusPayload = getRadiusPayload(stateMachine, stateMachine.identifier(), eapPacket);
                            radiusPayload = pktCustomizer.customizePacket(radiusPayload, inPacket);

                            if (stateMachine.challengeState() != null) {
                                radiusPayload.setAttribute(RADIUSAttribute.RADIUS_ATTR_STATE,
                                        stateMachine.challengeState());
                            }
                            stateMachine.setRequestAuthenticator(radiusPayload.generateAuthCode());

                            radiusPayload.addMessageAuthenticator(AaaManager.this.radiusSecret);
                            if (outPacketSupp.contains(eapPacket.getIdentifier())) {
                                aaaStatisticsManager.getAaaStats().decrementPendingResSupp();
                                outPacketSupp.remove(identifier);
                            }
                            sendRadiusPacket(radiusPayload, inPacket);
                            stateMachine.setWaitingForRadiusResponse(true);
                            aaaStatisticsManager.getAaaStats().incrementEapolTlsRespChall();

                            if (stateMachine.state() != StateMachine.STATE_PENDING) {
                                stateMachine.requestAccess();
                            }

                            break;
                        default:
                            log.warn("Unknown EAP packet type");
                            return;
                    }
                    break;
                default:
                    log.debug("Skipping EAPOL message {}", eapol.getEapolType());
            }
            aaaStatisticsManager.getAaaStats().countTransRespNotNak();
            aaaStatisticsManager.getAaaStats().countEapolResIdentityMsgTrans();
        }
    }

    /**
     * Delegate allowing the StateMachine to notify us of events.
     */
    private class InternalStateMachineDelegate implements StateMachineDelegate {

        @Override
        public void notify(AuthenticationEvent authenticationEvent) {
            log.info("Auth event {} for {}",
                    authenticationEvent.type(), authenticationEvent.subject());
            post(authenticationEvent);
        }
    }

    /**
     * Configuration Listener, handles change in configuration.
     */
    private class InternalConfigListener implements NetworkConfigListener {

        /**
         * Reconfigures the AAA application according to the
         * configuration parameters passed.
         *
         * @param cfg configuration object
         */
        private void reconfigureNetwork(AaaConfig cfg) {
            log.info("Reconfiguring AaaConfig from config: {}", cfg);

            if (cfg == null) {
                newCfg = new AaaConfig();
            } else {
                newCfg = cfg;
            }
            if (newCfg.nasIp() != null) {
                nasIpAddress = newCfg.nasIp();
            }
            if (newCfg.radiusIp() != null) {
                radiusIpAddress = newCfg.radiusIp();
            }
            if (newCfg.radiusMac() != null) {
                radiusMacAddress = newCfg.radiusMac();
            }
            if (newCfg.nasMac() != null) {
                nasMacAddress = newCfg.nasMac();
            }
            if (newCfg.radiusSecret() != null) {
                radiusSecret = newCfg.radiusSecret();
            }

            boolean reconfigureCustomizer = false;
            if (customizer == null || !customizer.equals(newCfg.radiusPktCustomizer())) {
                customizer = newCfg.radiusPktCustomizer();
                configurePacketCustomizer();
                reconfigureCustomizer = true;
            }

            if (radiusConnectionType == null
                    || reconfigureCustomizer
                    || !radiusConnectionType.equals(newCfg.radiusConnectionType())) {
                radiusConnectionType = newCfg.radiusConnectionType();
                if (impl != null) {
                    impl.withdrawIntercepts();
                    impl.clearLocalState();
                }
                configureRadiusCommunication();
                impl.initializeLocalState(newCfg);
                impl.requestIntercepts();
            } else if (impl != null) {
                impl.clearLocalState();
                impl.initializeLocalState(newCfg);
            }
        }

        @Override
        public void event(NetworkConfigEvent event) {

            if ((event.type() == NetworkConfigEvent.Type.CONFIG_ADDED ||
                    event.type() == NetworkConfigEvent.Type.CONFIG_UPDATED) &&
                    event.configClass().equals(AaaConfig.class)) {

                AaaConfig cfg = netCfgService.getConfig(appId, AaaConfig.class);
                reconfigureNetwork(cfg);

                log.info("Reconfigured: {}", cfg.toString());
            }
        }
    }

    private class InternalDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {

            switch (event.type()) {
                case PORT_REMOVED:
                    DeviceId devId = event.subject().id();
                    PortNumber portNumber = event.port().number();
                    String sessionId = devId.toString() + portNumber.toString();

                    log.info("Received PORT_REMOVED event. Clearing AAA Session with Id {}", sessionId);
                    flushStateMachineSession(sessionId,
                            StateMachine.SessionTerminationReasons.PORT_REMOVED.getReason());

                    break;

                case DEVICE_REMOVED:
                    DeviceId deviceId = event.subject().id();
                    log.info("Received DEVICE_REMOVED event for {}", deviceId);

                    Set<String> associatedSessions = Sets.newHashSet();
                    for (Entry<String, StateMachine> stateMachineEntry : StateMachine.sessionIdMap().entrySet()) {
                        ConnectPoint cp = stateMachineEntry.getValue().supplicantConnectpoint();
                        if (cp != null && cp.deviceId().toString().equals(deviceId.toString())) {
                            associatedSessions.add(stateMachineEntry.getKey());
                        }
                    }

                    for (String session : associatedSessions) {
                        log.info("Clearing AAA Session {} associated with Removed Device", session);
                        flushStateMachineSession(session,
                               StateMachine.SessionTerminationReasons.DEVICE_REMOVED.getReason());
                    }

                    break;

                default:
                    return;
            }
        }

        private void flushStateMachineSession(String sessionId, String terminationReason) {
            StateMachine stateMachine = StateMachine.lookupStateMachineBySessionId(sessionId);
            if (stateMachine != null) {
                stateMachine.setSessionTerminateReason(terminationReason);

                //pushing captured machine stats to kafka
                aaaSupplicantObj = aaaSupplicantStatsManager.getSupplicantStats(stateMachine);
                aaaSupplicantStatsManager.getMachineStatsDelegate()
                   .notify(new AaaMachineStatisticsEvent(
                           AaaMachineStatisticsEvent.Type.STATS_UPDATE, aaaSupplicantObj));

                Map<String, StateMachine> sessionIdMap = StateMachine.sessionIdMap();
                StateMachine removed = sessionIdMap.remove(sessionId);
                if (removed != null) {
                    StateMachine.deleteStateMachineMapping(removed);
                }
            }
        }
    }

    private class AuthenticationStatisticsEventPublisher implements Runnable {
        private final Logger log = getLogger(getClass());
        public void run() {
            log.info("Notifying AuthenticationStatisticsEvent");
            aaaStatisticsManager.calculatePacketRoundtripTime();
            log.debug("AcceptResponsesRx---" + aaaStatisticsManager.getAaaStats().getAcceptResponsesRx());
            log.debug("AccessRequestsTx---" + aaaStatisticsManager.getAaaStats().getAccessRequestsTx());
            log.debug("ChallengeResponsesRx---" + aaaStatisticsManager.getAaaStats().getChallengeResponsesRx());
            log.debug("DroppedResponsesRx---" + aaaStatisticsManager.getAaaStats().getDroppedResponsesRx());
            log.debug("InvalidValidatorsRx---" + aaaStatisticsManager.getAaaStats().getInvalidValidatorsRx());
            log.debug("MalformedResponsesRx---" + aaaStatisticsManager.getAaaStats().getMalformedResponsesRx());
            log.debug("PendingRequests---" + aaaStatisticsManager.getAaaStats().getPendingRequests());
            log.debug("RejectResponsesRx---" + aaaStatisticsManager.getAaaStats().getRejectResponsesRx());
            log.debug("RequestReTx---" + aaaStatisticsManager.getAaaStats().getRequestReTx());
            log.debug("RequestRttMilis---" + aaaStatisticsManager.getAaaStats().getRequestRttMilis());
            log.debug("UnknownServerRx---" + aaaStatisticsManager.getAaaStats().getUnknownServerRx());
            log.debug("UnknownTypeRx---" + aaaStatisticsManager.getAaaStats().getUnknownTypeRx());
            log.debug("TimedOutPackets----" + aaaStatisticsManager.getAaaStats().getTimedOutPackets());
            log.debug("EapolLogoffRx---" + aaaStatisticsManager.getAaaStats().getEapolLogoffRx());
            log.debug("EapolAuthSuccessTrans---" + aaaStatisticsManager.getAaaStats().getEapolAuthSuccessTrans());
            log.debug("EapolAuthFailureTrans---" +
            aaaStatisticsManager.getAaaStats().getEapolAuthFailureTrans());
            log.debug("EapolStartReqTrans---" +
            aaaStatisticsManager.getAaaStats().getEapolStartReqTrans());
            log.debug("EapolTransRespNotNak---" +
            aaaStatisticsManager.getAaaStats().getEapolTransRespNotNak());
            log.debug("EapPktTxauthChooseEap---" +
            aaaStatisticsManager.getAaaStats().getEapPktTxauthChooseEap());
            log.debug("EapolResIdentityMsgTrans---" +
            aaaStatisticsManager.getAaaStats().getEapolResIdentityMsgTrans());
            log.debug("EapolFramesTx---" + aaaStatisticsManager.getAaaStats().getEapolFramesTx());
            log.debug("AuthStateIdle---" + aaaStatisticsManager.getAaaStats().getAuthStateIdle());
            log.debug("RequestIdFramesTx---" + aaaStatisticsManager.getAaaStats().getRequestIdFramesTx());
            log.debug("ReqEapFramesTx---" + aaaStatisticsManager.getAaaStats().getReqEapFramesTx());
            log.debug("InvalidPktType---" + aaaStatisticsManager.getAaaStats().getInvalidPktType());
            log.debug("InvalidBodyLength---" + aaaStatisticsManager.getAaaStats().getInvalidBodyLength());
            log.debug("ValidEapolFramesRx---" + aaaStatisticsManager.getAaaStats().getValidEapolFramesRx());
            log.debug("PendingResSupp---" + aaaStatisticsManager.getAaaStats().getPendingResSupp());
            log.debug("ResIdEapFramesRx---" + aaaStatisticsManager.getAaaStats().getEapolattrIdentity());
            aaaStatisticsManager.getStatsDelegate().
                notify(new AuthenticationStatisticsEvent(AuthenticationStatisticsEvent.Type.STATS_UPDATE,
                    aaaStatisticsManager.getAaaStats()));
        }
    }

    private class ServerStatusChecker implements Runnable {
        @Override
        public void run() {
            log.info("Notifying RadiusOperationalStatusEvent");
            radiusOperationalStatusService.checkServerOperationalStatus();
            log.info("--POSTING--" + radiusOperationalStatusService.getRadiusServerOperationalStatus());
            radiusOperationalStatusService.getRadiusOprStDelegate()
                .notify(new RadiusOperationalStatusEvent(
                        RadiusOperationalStatusEvent.Type.RADIUS_OPERATIONAL_STATUS,
                        radiusOperationalStatusService.
                        getRadiusServerOperationalStatus()));
        }

    }
}
