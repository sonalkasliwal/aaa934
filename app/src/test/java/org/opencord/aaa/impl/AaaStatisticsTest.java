/*
 * Copyright 2015-present Open Networking Foundation
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

import com.google.common.base.Charsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.junit.TestUtils;
import org.onlab.packet.BasePacket;
import org.onlab.packet.DeserializationException;
import org.onlab.packet.EAP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpAddress;
import org.onlab.packet.RADIUS;
import org.onlab.packet.RADIUSAttribute;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreServiceAdapter;
import org.onosproject.event.DefaultEventSinkRegistry;
import org.onosproject.event.Event;
import org.onosproject.event.EventDeliveryService;
import org.onosproject.event.EventSink;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.Annotations;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.DefaultDevice;
import org.onosproject.net.DefaultPort;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.Port.Type;
import org.onosproject.net.PortNumber;
import org.onosproject.net.SparseAnnotations;
import org.onosproject.net.config.Config;
import org.onosproject.net.config.NetworkConfigRegistryAdapter;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.packet.DefaultInboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketService;
import org.opencord.aaa.AaaConfig;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import static com.google.common.base.Preconditions.checkState;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.onosproject.net.NetTestTools.connectPoint;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Set of tests of the ONOS application component for AAA Statistics.
 */
public class AaaStatisticsTest extends AaaTestBase {

    static final String BAD_IP_ADDRESS = "198.51.100.0";
    static final Long ZERO = (long) 0;

    private final Logger log = getLogger(getClass());
    private AaaManager aaaManager;
    private AaaStatisticsManager aaaStatisticsManager;
    private AaaSupplicantMachineStatsManager aaaSupplicantStatsManager;

    class AaaManagerWithoutRadiusServer extends AaaManager {
       protected void sendRadiusPacket(RADIUS radiusPacket, InboundPacket inPkt) {
          super.sendRadiusPacket(radiusPacket, inPkt);
          aaaManager.aaaStatisticsManager.putOutgoingIdentifierToMap(radiusPacket.getIdentifier());
          savePacket(radiusPacket);
       }

       // changed the configuration of parent method to protected
       protected void configureRadiusCommunication() {
           PacketService pktService = new MockPacketService();
           ApplicationId appId = new CoreServiceAdapter().registerApplication("org.opencord.aaa");
           aaaManager.impl = new TestSocketBasedRadiusCommunicator(appId, pktService, aaaManager);
           }
       }

    /**
     * Mocks the AAAConfig class to force usage of an unroutable address for the
     * RADIUS server.
     */
    static class MockAaaConfig extends AaaConfig {
        @Override
        public InetAddress radiusIp() {
          try {
                return InetAddress.getByName(BAD_IP_ADDRESS);
              } catch (UnknownHostException ex) {
                throw new IllegalStateException(ex);
               }
        }
       }

    /**
     * Mocks the network config registry.
     */
    @SuppressWarnings("unchecked")
    private static final class TestNetworkConfigRegistry extends NetworkConfigRegistryAdapter {
        @Override
        public <S, C extends Config<S>> C getConfig(S subject, Class<C> configClass) {
            AaaConfig aaaConfig = new MockAaaConfig();
            return (C) aaaConfig;
         }
    }

    public static class TestEventDispatcher extends DefaultEventSinkRegistry implements EventDeliveryService {

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void post(Event event) {
       EventSink sink = getSink(event.getClass());
       checkState(sink != null, "No sink for event %s", event);
       sink.process(event);
     }

       @Override
       public void setDispatchTimeLimit(long millis) {
       }

       @Override
       public long getDispatchTimeLimit() {
         return 0;
       }
     }

    /**
     * Constructs an Ethernet packet containing a RADIUS challenge packet.
     *
     * @param challengeCode
     * code to use in challenge packet
     * @param challengeType
     *            type to use in challenge packet
     * @return Ethernet packet
     */
   private RADIUS constructRadiusCodeAccessChallengePacket(byte challengeCode, byte challengeType) {

       String challenge = "12345678901234567";

       EAP eap = new EAP(challengeType, (byte) 4, challengeType,
                         challenge.getBytes(Charsets.US_ASCII));
       eap.setIdentifier((byte) 4);

       RADIUS radius = new RADIUS();
       radius.setCode(challengeCode);
       radius.setIdentifier((byte) 4);
       radius.setAttribute(RADIUSAttribute.RADIUS_ATTR_STATE,
                           challenge.getBytes(Charsets.US_ASCII));

       radius.setPayload(eap);
       radius.setAttribute(RADIUSAttribute.RADIUS_ATTR_EAP_MESSAGE,
                           eap.serialize());
       radius.setAttribute(RADIUSAttribute.RADIUS_ATTR_MESSAGE_AUTH,
               aaaManager.radiusSecret.getBytes());
       return radius;
   }

    public static void injectEventDispatcher(Object manager, EventDeliveryService svc) {
       Class mc = manager.getClass();
       for (Field f : mc.getSuperclass().getDeclaredFields()) {
          if (f.getType().equals(EventDeliveryService.class)) {
           try {
                 TestUtils.setField(manager, f.getName(), svc);
               } catch (TestUtils.TestUtilsException e) {
                    throw new IllegalArgumentException("Unable to inject reference", e);
               }
                break;
          }
      }
    }

/**
 * Set up the services required by the AAA application.
 */
    @Before
    public void setUp() {
        aaaManager = new AaaManagerWithoutRadiusServer();
        aaaManager.radiusOperationalStatusService = new RadiusOperationalStatusManager();
        aaaManager.netCfgService = new TestNetworkConfigRegistry();
        aaaManager.coreService = new CoreServiceAdapter();
        aaaManager.packetService = new MockPacketService();
        aaaManager.deviceService = new TestDeviceService();
        aaaManager.sadisService = new MockSadisService();
        aaaManager.cfgService = new MockCfgService();
        aaaStatisticsManager = new AaaStatisticsManager();
        aaaSupplicantStatsManager = new AaaSupplicantMachineStatsManager();
        TestUtils.setField(aaaStatisticsManager, "eventDispatcher", new TestEventDispatcher());
        aaaStatisticsManager.activate();
        TestUtils.setField(aaaSupplicantStatsManager, "eventDispatcher", new TestEventDispatcher());
        aaaSupplicantStatsManager.activate();
        aaaManager.aaaStatisticsManager = this.aaaStatisticsManager;
        aaaManager.aaaSupplicantStatsManager = this.aaaSupplicantStatsManager;
        TestUtils.setField(aaaManager, "eventDispatcher", new TestEventDispatcher());
        aaaManager.activate(new AaaTestBase.MockComponentContext());
    }

/**
 * Tear down the AAA application.
 */
@After
public void tearDown() {
  aaaManager.deactivate(new AaaTestBase.MockComponentContext());
}

/**
 * Extracts the RADIUS packet from a packet sent by the supplicant.
 *
 * @param radius
 *            RADIUS packet sent by the supplicant
 * @throws DeserializationException
 *             if deserialization of the packet contents fails.
 */
private void checkRadiusPacketFromSupplicant(RADIUS radius) throws DeserializationException {
   assertThat(radius, notNullValue());
   EAP eap = radius.decapsulateMessage();
   assertThat(eap, notNullValue());
}

/**
 * Fetches the sent packet at the given index. The requested packet must be the
 * last packet on the list.
 *
 * @param index
 *            index into sent packets array
 * @return packet
 */
private BasePacket fetchPacket(int index) {
    BasePacket packet = savedPackets.get(index);
    assertThat(packet, notNullValue());
    return packet;
}

    /** Tests the authentication path through the AAA application.
     *  And counts the aaa Stats for successful transmission.
     *   @throws DeserializationException
     *  if packed deserialization fails.
     */
    @Test
    public void testAaaStatisticsForAcceptedPackets() throws Exception {

        // (1) Supplicant start up
        Ethernet startPacket = constructSupplicantStartPacket();
        sendPacket(startPacket);

        Ethernet responsePacket = (Ethernet) fetchPacket(0);
        checkRadiusPacket(aaaManager, responsePacket, EAP.ATTR_IDENTITY);

        // (2) Supplicant identify

        Ethernet identifyPacket = constructSupplicantIdentifyPacket(null, EAP.ATTR_IDENTITY, (byte) 1, null);
        sendPacket(identifyPacket);

        RADIUS radiusIdentifyPacket = (RADIUS) fetchPacket(1);
        checkRadiusPacketFromSupplicant(radiusIdentifyPacket);

        assertThat(radiusIdentifyPacket.getCode(), is(RADIUS.RADIUS_CODE_ACCESS_REQUEST));
        assertThat(new String(radiusIdentifyPacket.getAttribute(RADIUSAttribute.RADIUS_ATTR_USERNAME).getValue()),
                is("testuser"));
        IpAddress nasIp = IpAddress.valueOf(IpAddress.Version.INET,
                  radiusIdentifyPacket.getAttribute(RADIUSAttribute.RADIUS_ATTR_NAS_IP).getValue());
        assertThat(nasIp.toString(), is(aaaManager.nasIpAddress.getHostAddress()));

        // State machine should have been created by now

        StateMachine stateMachine = StateMachine.lookupStateMachineBySessionId(SESSION_ID);
        assertThat(stateMachine, notNullValue());
        assertThat(stateMachine.state(), is(StateMachine.STATE_PENDING));

        // (3) RADIUS MD5 challenge

       RADIUS radiusCodeAccessChallengePacket = constructRadiusCodeAccessChallengePacket(
                  RADIUS.RADIUS_CODE_ACCESS_CHALLENGE, EAP.ATTR_MD5);
        aaaManager.handleRadiusPacket(radiusCodeAccessChallengePacket);

        Ethernet radiusChallengeMD5Packet = (Ethernet) fetchPacket(2);
        checkRadiusPacket(aaaManager, radiusChallengeMD5Packet, EAP.ATTR_MD5);

        // (4) Supplicant MD5 response

       Ethernet md5RadiusPacket = constructSupplicantIdentifyPacket(stateMachine, EAP.ATTR_MD5,
           stateMachine.challengeIdentifier(), radiusChallengeMD5Packet);
       sendPacket(md5RadiusPacket);

        RADIUS responseMd5RadiusPacket = (RADIUS) fetchPacket(3);

        checkRadiusPacketFromSupplicant(responseMd5RadiusPacket);
        assertThat(responseMd5RadiusPacket.getIdentifier(), is((byte) 9));
        assertThat(responseMd5RadiusPacket.getCode(), is(RADIUS.RADIUS_CODE_ACCESS_REQUEST));

        // State machine should be in pending state

        assertThat(stateMachine, notNullValue());
        assertThat(stateMachine.state(), is(StateMachine.STATE_PENDING));

        // (5) RADIUS Success

        RADIUS successPacket =
                constructRadiusCodeAccessChallengePacket(RADIUS.RADIUS_CODE_ACCESS_ACCEPT, EAP.SUCCESS);
        aaaManager.handleRadiusPacket((successPacket));
        Ethernet supplicantSuccessPacket = (Ethernet) fetchPacket(4);

        checkRadiusPacket(aaaManager, supplicantSuccessPacket, EAP.SUCCESS);

        // State machine should be in authorized state

        assertThat(stateMachine, notNullValue());
        assertThat(stateMachine.state(), is(StateMachine.STATE_AUTHORIZED));

        //Check for increase of Stats
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolResIdentityMsgTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolAuthSuccessTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolStartReqTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolTransRespNotNak(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapPktTxauthChooseEap(), ZERO);

        assertNotEquals(aaaStatisticsManager.getAaaStats().getAcceptResponsesRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getAccessRequestsTx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getChallengeResponsesRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getDroppedResponsesRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getInvalidValidatorsRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getPendingRequests(), ZERO);

        // Counts the aaa Statistics count and displays in the log
        countAaaStatistics();
    }

    /** Tests the count for defected packets.
     *
     *   @throws DeserializationException
     * if packed deserialization fails.
     */
    @Test
    public void testAaaStatisticsForDefectivePackets() throws Exception {
        // (1) Supplicant start up
        Ethernet startPacket = constructSupplicantStartPacket();
        sendPacket(startPacket);

        // (2) Supplicant identify

        Ethernet identifyPacket = constructSupplicantIdentifyPacket(null, EAP.ATTR_IDENTITY, (byte) 1, null);
        sendPacket(identifyPacket);

        RADIUS radiusIdentifyPacket = (RADIUS) fetchPacket(1);

        checkRadiusPacketFromSupplicant(radiusIdentifyPacket);

        // Calling the mock test socket based to handle packet
        aaaManager.impl.handlePacketFromServer(null);
        // State machine should have been created by now

        StateMachine stateMachine = StateMachine.lookupStateMachineBySessionId(SESSION_ID);

        // (3) RADIUS MD5 challenge

        RADIUS radiusCodeAccessChallengePacket = constructRadiusCodeAccessChallengePacket(
               RADIUS.RADIUS_CODE_ACCESS_CHALLENGE, EAP.ATTR_MD5);
        aaaManager.handleRadiusPacket(radiusCodeAccessChallengePacket);

        Ethernet radiusChallengeMD5Packet = (Ethernet) fetchPacket(2);

        // (4) Supplicant MD5 response

        Ethernet md5RadiusPacket = constructSupplicantIdentifyPacket(stateMachine, EAP.ATTR_MD5,
              stateMachine.challengeIdentifier(), radiusChallengeMD5Packet);
        sendPacket(md5RadiusPacket);
        aaaManager.aaaStatisticsManager.calculatePacketRoundtripTime();
        // (5) RADIUS Rejected

        RADIUS rejectedPacket =
               constructRadiusCodeAccessChallengePacket(RADIUS.RADIUS_CODE_ACCESS_REJECT, EAP.FAILURE);
        aaaManager.handleRadiusPacket((rejectedPacket));
        Ethernet supplicantRejectedPacket = (Ethernet) fetchPacket(4);

        checkRadiusPacket(aaaManager, supplicantRejectedPacket, EAP.FAILURE);

        // State machine should be in unauthorized state
        assertThat(stateMachine, notNullValue());
        assertThat(stateMachine.state(), is(StateMachine.STATE_UNAUTHORIZED));
        // Calculated the total round trip time
        aaaManager.aaaStatisticsManager.calculatePacketRoundtripTime();

        //Check for increase of Stats
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolResIdentityMsgTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolAuthFailureTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolStartReqTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapPktTxauthChooseEap(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolTransRespNotNak(), ZERO);

        assertNotEquals(aaaStatisticsManager.getAaaStats().getAccessRequestsTx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getChallengeResponsesRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getDroppedResponsesRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getInvalidValidatorsRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getPendingRequests(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getRejectResponsesRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getRequestRttMilis(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getUnknownTypeRx(), ZERO);

        // Counts the aaa Statistics count
        countAaaStatistics();

     }

    /*
     * Tests the retransmitted packet and malformed packet count
     *
     * @throws DeserializationException
     *  if packed deserialization fails.
     */
    @Test
    public void testRequestRetransmittedCount() throws Exception {

        // (1) Supplicant start up
        Ethernet startPacket = constructSupplicantStartPacket();
        sendPacket(startPacket);

        // (2) Supplicant identify

        Ethernet identifyPacket = constructSupplicantIdentifyPacket(null, EAP.ATTR_IDENTITY, (byte) 1, null);
        sendPacket(identifyPacket);

        RADIUS radiusIdentifyPacket = (RADIUS) fetchPacket(1);
        checkRadiusPacketFromSupplicant(radiusIdentifyPacket);

        // again creating pending state for same packet
        constructSupplicantIdentifyPacket(null, EAP.ATTR_IDENTITY, (byte) 1, null);
        sendPacket(identifyPacket);
        aaaManager.impl.handlePacketFromServer(null);
        aaaManager.aaaStatisticsManager.calculatePacketRoundtripTime();

        // creating malformed packet
        final ByteBuffer byteBuffer = ByteBuffer.wrap(startPacket.serialize());
        InboundPacket inPacket = new DefaultInboundPacket(connectPoint("1", 1),
              startPacket, byteBuffer);

        PacketContext context = new TestPacketContext(127L, inPacket, null, false);
        aaaManager.impl.handlePacketFromServer(context);

        // Check for increase of Stats
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolResIdentityMsgTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolStartReqTrans(), ZERO);

        assertNotEquals(aaaStatisticsManager.getAaaStats().getAccessRequestsTx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getDroppedResponsesRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getPendingRequests(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getMalformedResponsesRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getRequestReTx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getRequestRttMilis(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getUnknownTypeRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getUnknownServerRx(), ZERO);

        countAaaStatistics();
    }

    /** Tests the authentication path through the AAA application.
     *  And counts the aaa Stats for logoff transactionXZ.
     *   @throws DeserializationException
     *  if packed deserialization fails.
     */
    @Test
    public void testAaaStatisticsForLogoffPackets() throws Exception {

        // (1) Supplicant start up
        Ethernet startPacket = constructSupplicantStartPacket();
        sendPacket(startPacket);

        Ethernet responsePacket = (Ethernet) fetchPacket(0);
        checkRadiusPacket(aaaManager, responsePacket, EAP.ATTR_IDENTITY);

        // (2) Supplicant identify

        Ethernet identifyPacket = constructSupplicantIdentifyPacket(null, EAP.ATTR_IDENTITY, (byte) 1, null);
        sendPacket(identifyPacket);

        RADIUS radiusIdentifyPacket = (RADIUS) fetchPacket(1);
        checkRadiusPacketFromSupplicant(radiusIdentifyPacket);

        assertThat(radiusIdentifyPacket.getCode(), is(RADIUS.RADIUS_CODE_ACCESS_REQUEST));
        assertThat(new String(radiusIdentifyPacket.getAttribute(RADIUSAttribute.RADIUS_ATTR_USERNAME).getValue()),
                is("testuser"));
        IpAddress nasIp = IpAddress.valueOf(IpAddress.Version.INET,
                  radiusIdentifyPacket.getAttribute(RADIUSAttribute.RADIUS_ATTR_NAS_IP).getValue());
        assertThat(nasIp.toString(), is(aaaManager.nasIpAddress.getHostAddress()));

        // State machine should have been created by now

        StateMachine stateMachine = StateMachine.lookupStateMachineBySessionId(SESSION_ID);
        assertThat(stateMachine, notNullValue());
        assertThat(stateMachine.state(), is(StateMachine.STATE_PENDING));

        // (3) RADIUS MD5 challenge

       RADIUS radiusCodeAccessChallengePacket = constructRadiusCodeAccessChallengePacket(
                  RADIUS.RADIUS_CODE_ACCESS_CHALLENGE, EAP.ATTR_MD5);
        aaaManager.handleRadiusPacket(radiusCodeAccessChallengePacket);

        Ethernet radiusChallengeMD5Packet = (Ethernet) fetchPacket(2);
        checkRadiusPacket(aaaManager, radiusChallengeMD5Packet, EAP.ATTR_MD5);

        // (4) Supplicant MD5 response

       Ethernet md5RadiusPacket = constructSupplicantIdentifyPacket(stateMachine, EAP.ATTR_MD5,
           stateMachine.challengeIdentifier(), radiusChallengeMD5Packet);
       sendPacket(md5RadiusPacket);

        RADIUS responseMd5RadiusPacket = (RADIUS) fetchPacket(3);

        checkRadiusPacketFromSupplicant(responseMd5RadiusPacket);
        assertThat(responseMd5RadiusPacket.getIdentifier(), is((byte) 9));
        assertThat(responseMd5RadiusPacket.getCode(), is(RADIUS.RADIUS_CODE_ACCESS_REQUEST));

        // State machine should be in pending state

        assertThat(stateMachine, notNullValue());
        assertThat(stateMachine.state(), is(StateMachine.STATE_PENDING));

        // (5) RADIUS Success

        RADIUS successPacket =
                constructRadiusCodeAccessChallengePacket(RADIUS.RADIUS_CODE_ACCESS_ACCEPT, EAP.SUCCESS);
        aaaManager.handleRadiusPacket((successPacket));
        Ethernet supplicantSuccessPacket = (Ethernet) fetchPacket(4);

        checkRadiusPacket(aaaManager, supplicantSuccessPacket, EAP.SUCCESS);

        // State machine should be in authorized state

        assertThat(stateMachine, notNullValue());
        assertThat(stateMachine.state(), is(StateMachine.STATE_AUTHORIZED));

        // Supplicant trigger EAP Logoff
        Ethernet loggoffPacket = constructSupplicantLogoffPacket();
        sendPacket(loggoffPacket);

        // State machine should be in logoff state
        assertThat(stateMachine, notNullValue());
        assertThat(stateMachine.state(), is(StateMachine.STATE_IDLE));

        //Check for increase in stats
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolLogoffRx(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolResIdentityMsgTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolAuthSuccessTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolStartReqTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolTransRespNotNak(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapPktTxauthChooseEap(), ZERO);
       // Counts the aaa Statistics count
       countAaaStatistics();

    }


    /** Tests the authentication path through the AAA application.
     *  And counts the aaa Stats for timeout.
     *   @throws DeserializationException
     *  if packed deserialization fails.
     */
    @Test
    public void testAaaStatisticsForTimeoutPackets() throws Exception {

        // (1) Supplicant start up
        Ethernet startPacket = constructSupplicantStartPacket();
        sendPacket(startPacket);

        Ethernet responsePacket = (Ethernet) fetchPacket(0);
        checkRadiusPacket(aaaManager, responsePacket, EAP.ATTR_IDENTITY);

        // (2) Supplicant identify

        Ethernet identifyPacket = constructSupplicantIdentifyPacket(null, EAP.ATTR_IDENTITY, (byte) 1, null);
        sendPacket(identifyPacket);

        RADIUS radiusIdentifyPacket = (RADIUS) fetchPacket(1);
        checkRadiusPacketFromSupplicant(radiusIdentifyPacket);

        assertThat(radiusIdentifyPacket.getCode(), is(RADIUS.RADIUS_CODE_ACCESS_REQUEST));
        assertThat(new String(radiusIdentifyPacket.getAttribute(RADIUSAttribute.RADIUS_ATTR_USERNAME).getValue()),
                is("testuser"));
        IpAddress nasIp = IpAddress.valueOf(IpAddress.Version.INET,
                  radiusIdentifyPacket.getAttribute(RADIUSAttribute.RADIUS_ATTR_NAS_IP).getValue());
        assertThat(nasIp.toString(), is(aaaManager.nasIpAddress.getHostAddress()));

        // State machine should have been created by now

        StateMachine stateMachine = StateMachine.lookupStateMachineBySessionId(SESSION_ID);
        assertThat(stateMachine, notNullValue());
        assertThat(stateMachine.state(), is(StateMachine.STATE_PENDING));
        Thread.sleep((aaaManager.cleanupTimerTimeOutInMins / 2) + 1);

        // State machine should be in timeout state
        assertThat(stateMachine, notNullValue());
        assertThat(stateMachine.state(), is(StateMachine.STATE_PENDING));

        //Check for increase in stats
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolResIdentityMsgTrans(), ZERO);
        assertNotEquals(aaaStatisticsManager.getAaaStats().getEapolStartReqTrans(), ZERO);
       countAaaStatistics();

    }

    /** Tests the authentication path through the AAA application.
     *  And counts the aaa supplicant Stats for unauthenticated onu (Port removed)
     *   @throws DeserializationException
     *  if packed deserialization fails.
     */
    @Test
    public void testAaaSuplicantStatsForUnauthenticatedOnu() throws Exception {

        // Device configuration
        DeviceId deviceId = DeviceId.deviceId("of:1");
        // Source ip address of two different device.
        Ip4Address sourceIp = Ip4Address.valueOf("10.177.125.4");
        DefaultAnnotations.Builder annotationsBuilder = DefaultAnnotations.builder()
                .set(AnnotationKeys.MANAGEMENT_ADDRESS, sourceIp.toString());
        SparseAnnotations annotations = annotationsBuilder.build();
        Annotations[] deviceAnnotations = {annotations };
        Device deviceA = new DefaultDevice(null, deviceId, Device.Type.OTHER, "", "", "", "", null, deviceAnnotations);
        Port port =
             new DefaultPort(null, PortNumber.portNumber(1), true, Type.COPPER, DefaultPort.DEFAULT_SPEED, annotations);
        DeviceEvent deviceEvent = new DeviceEvent(DeviceEvent.Type.PORT_REMOVED, deviceA, port);
        aaaManager.deviceListener.event(deviceEvent);
        // Check the aaa supplicant stats object is null.
        assertNull(aaaManager.aaaSupplicantObj);
    }

    // Calculates the AAA statistics count.
    public void countAaaStatistics() {
        assertThat(aaaStatisticsManager.getAaaStats().getAcceptResponsesRx(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getAccessRequestsTx(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getChallengeResponsesRx(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getDroppedResponsesRx(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getInvalidValidatorsRx(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getMalformedResponsesRx(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getPendingRequests(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getRejectResponsesRx(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getRequestReTx(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getRequestRttMilis(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getUnknownServerRx(), notNullValue());
        assertThat(aaaStatisticsManager.getAaaStats().getUnknownTypeRx(), notNullValue());

    }

    /*
     * Mock implementation of SocketBasedRadiusCommunicator class.
     *
     */
    class TestSocketBasedRadiusCommunicator extends SocketBasedRadiusCommunicator {

      TestSocketBasedRadiusCommunicator(ApplicationId appId, PacketService pktService, AaaManager aaaManager) {
          super(appId, pktService, aaaManager);
      }

        // Implementation of socketBasedRadiusCommunicator--> run() method
        public void handlePacketFromServer(PacketContext context) {

           RADIUS incomingPkt = (RADIUS) fetchPacket(savedPackets.size() - 1);
           try {
                 if (context == null) {
                    aaaStatisticsManager.handleRoundtripTime(incomingPkt.getIdentifier());
                    aaaManager.handleRadiusPacket(incomingPkt);
                } else if (null != context) {
                    aaaManager.checkForPacketFromUnknownServer("100.100.100.0");
                    aaaStatisticsManager.handleRoundtripTime(incomingPkt.getIdentifier());
                    aaaManager.handleRadiusPacket(incomingPkt);
                    incomingPkt =
                            RADIUS.deserializer().deserialize(incomingPkt.generateAuthCode(), 0, 1);
                }
                } catch (DeserializationException dex) {
                    aaaManager.aaaStatisticsManager.getAaaStats().increaseMalformedResponsesRx();
                    aaaStatisticsManager.getAaaStats().countDroppedResponsesRx();
                    log.error("Cannot deserialize packet", dex);
                } catch (StateMachineException sme) {
                    log.error("Illegal state machine operation", sme);
        }

        }

    }

}