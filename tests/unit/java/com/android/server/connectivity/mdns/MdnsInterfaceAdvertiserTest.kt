/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity.mdns

import android.net.InetAddresses.parseNumericAddress
import android.net.LinkAddress
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.HandlerThread
import com.android.net.module.util.HexDump
import com.android.net.module.util.SharedLog
import com.android.server.connectivity.mdns.MdnsAnnouncer.AnnouncementInfo
import com.android.server.connectivity.mdns.MdnsAnnouncer.BaseAnnouncementInfo
import com.android.server.connectivity.mdns.MdnsAnnouncer.ExitAnnouncementInfo
import com.android.server.connectivity.mdns.MdnsInterfaceAdvertiser.CONFLICT_SERVICE
import com.android.server.connectivity.mdns.MdnsInterfaceAdvertiser.EXIT_ANNOUNCEMENT_DELAY_MS
import com.android.server.connectivity.mdns.MdnsPacketRepeater.PacketRepeaterCallback
import com.android.server.connectivity.mdns.MdnsProber.ProbingInfo
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.waitForIdle
import java.net.InetSocketAddress
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.argThat
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.inOrder

private const val LOG_TAG = "testlogtag"
private const val TIMEOUT_MS = 10_000L

private val TEST_ADDRS = listOf(LinkAddress(parseNumericAddress("2001:db8::123"), 64))
private val TEST_BUFFER = ByteArray(1300)
private val TEST_HOSTNAME = arrayOf("Android_test", "local")

private const val TEST_SERVICE_ID_1 = 42
private const val TEST_SERVICE_ID_DUPLICATE = 43
private const val TEST_SERVICE_ID_2 = 44
private val TEST_SERVICE_1 = NsdServiceInfo().apply {
    serviceType = "_testservice._tcp"
    serviceName = "MyTestService"
    port = 12345
}

private val TEST_SERVICE_1_SUBTYPE = NsdServiceInfo().apply {
    subtypes = setOf("_sub")
    serviceType = "_testservice._tcp"
    serviceName = "MyTestService"
    port = 12345
}

private val TEST_SERVICE_1_CUSTOM_HOST = NsdServiceInfo().apply {
    serviceType = "_testservice._tcp"
    serviceName = "MyTestService"
    hostname = "MyTestHost"
    port = 12345
}

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsInterfaceAdvertiserTest {
    private val socket = mock(MdnsInterfaceSocket::class.java)
    private val thread = HandlerThread(MdnsInterfaceAdvertiserTest::class.simpleName)
    private val cb = mock(MdnsInterfaceAdvertiser.Callback::class.java)
    private val deps = mock(MdnsInterfaceAdvertiser.Dependencies::class.java)
    private val repository = mock(MdnsRecordRepository::class.java)
    private val replySender = mock(MdnsReplySender::class.java)
    private val announcer = mock(MdnsAnnouncer::class.java)
    private val prober = mock(MdnsProber::class.java)
    private val sharedlog = SharedLog("MdnsInterfaceAdvertiserTest")
    private val flags = MdnsFeatureFlags.newBuilder()
            .setIsKnownAnswerSuppressionEnabled(true).build()
    @Suppress("UNCHECKED_CAST")
    private val probeCbCaptor = ArgumentCaptor.forClass(PacketRepeaterCallback::class.java)
            as ArgumentCaptor<PacketRepeaterCallback<ProbingInfo>>
    @Suppress("UNCHECKED_CAST")
    private val announceCbCaptor = ArgumentCaptor.forClass(PacketRepeaterCallback::class.java)
            as ArgumentCaptor<PacketRepeaterCallback<BaseAnnouncementInfo>>
    private val packetHandlerCaptor = ArgumentCaptor.forClass(
            MulticastPacketReader.PacketHandler::class.java)

    private val probeCb get() = probeCbCaptor.value
    private val announceCb get() = announceCbCaptor.value
    private val packetHandler get() = packetHandlerCaptor.value

    private val advertiser by lazy {
        MdnsInterfaceAdvertiser(
            socket,
            TEST_ADDRS,
            thread.looper,
            TEST_BUFFER,
            cb,
            deps,
            TEST_HOSTNAME,
            sharedlog,
            flags
        )
    }

    @Before
    fun setUp() {
        doReturn(repository).`when`(deps).makeRecordRepository(any(), eq(TEST_HOSTNAME), any())
        doReturn(replySender).`when`(deps).makeReplySender(
                anyString(), any(), any(), any(), any(), any())
        doReturn(announcer).`when`(deps).makeMdnsAnnouncer(anyString(), any(), any(), any(), any())
        doReturn(prober).`when`(deps).makeMdnsProber(anyString(), any(), any(), any(), any())

        val knownServices = mutableSetOf<Int>()
        doAnswer { inv ->
            knownServices.add(inv.getArgument(0))

            -1
        }.`when`(repository).addService(anyInt(), any(), any())
        doAnswer { inv ->
            knownServices.remove(inv.getArgument(0))
            null
        }.`when`(repository).removeService(anyInt())
        doAnswer {
            knownServices.toIntArray().also { knownServices.clear() }
        }.`when`(repository).clearServices()
        doAnswer { inv ->
            knownServices.contains(inv.getArgument(0))
        }.`when`(repository).hasActiveService(anyInt())
        thread.start()
        advertiser.start()

        verify(socket).addPacketHandler(packetHandlerCaptor.capture())
        verify(deps).makeMdnsProber(any(), any(), any(), probeCbCaptor.capture(), any())
        verify(deps).makeMdnsAnnouncer(any(), any(), any(), announceCbCaptor.capture(), any())
    }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    @Test
    fun testAddRemoveService() {
        val testAnnouncementInfo = addServiceAndFinishProbing(TEST_SERVICE_ID_1, TEST_SERVICE_1)

        verify(announcer).startSending(TEST_SERVICE_ID_1, testAnnouncementInfo,
                0L /* initialDelayMs */)

        thread.waitForIdle(TIMEOUT_MS)
        verify(cb).onServiceProbingSucceeded(advertiser, TEST_SERVICE_ID_1)

        // Remove the service: expect exit announcements
        val testExitInfo = mock(ExitAnnouncementInfo::class.java)
        doReturn(testExitInfo).`when`(repository).exitService(TEST_SERVICE_ID_1)
        advertiser.removeService(TEST_SERVICE_ID_1)

        verify(prober).stop(TEST_SERVICE_ID_1)
        verify(announcer).stop(TEST_SERVICE_ID_1)
        verify(announcer).startSending(TEST_SERVICE_ID_1, testExitInfo, EXIT_ANNOUNCEMENT_DELAY_MS)

        // Exit announcements finish: the advertiser has no left service and destroys itself
        announceCb.onFinished(testExitInfo)
        thread.waitForIdle(TIMEOUT_MS)
        verify(cb).onAllServicesRemoved(socket)
    }

    @Test
    fun testAddRemoveServiceWithCustomHost_restartProbingForProbingServices() {
        val customHost1 = NsdServiceInfo().apply {
            hostname = "MyTestHost"
            hostAddresses = listOf(
                    parseNumericAddress("192.0.2.23"),
                    parseNumericAddress("2001:db8::1"))
        }
        addServiceAndFinishProbing(TEST_SERVICE_ID_1, customHost1)
        addServiceAndFinishProbing(TEST_SERVICE_ID_2, TEST_SERVICE_1_CUSTOM_HOST)
        repository.setServiceProbing(TEST_SERVICE_ID_2)
        val probingInfo = mock(ProbingInfo::class.java)
        doReturn("MyTestHost")
                .`when`(repository).getHostnameForServiceId(TEST_SERVICE_ID_1)
        doReturn(TEST_SERVICE_ID_2).`when`(probingInfo).serviceId
        doReturn(listOf(probingInfo))
                .`when`(repository).restartProbingForHostname("MyTestHost")
        val inOrder = inOrder(prober, announcer)

        // Remove the custom host: the custom host's announcement is stopped and the probing
        // services which use that hostname are re-announced.
        advertiser.removeService(TEST_SERVICE_ID_1)

        inOrder.verify(prober).stop(TEST_SERVICE_ID_1)
        inOrder.verify(announcer).stop(TEST_SERVICE_ID_1)
        inOrder.verify(prober).stop(TEST_SERVICE_ID_2)
        inOrder.verify(prober).startProbing(probingInfo)
    }

    @Test
    fun testAddRemoveServiceWithCustomHost_restartAnnouncingForProbedServices() {
        val customHost1 = NsdServiceInfo().apply {
            hostname = "MyTestHost"
            hostAddresses = listOf(
                    parseNumericAddress("192.0.2.23"),
                    parseNumericAddress("2001:db8::1"))
        }
        addServiceAndFinishProbing(TEST_SERVICE_ID_1, customHost1)
        val announcementInfo =
                addServiceAndFinishProbing(TEST_SERVICE_ID_2, TEST_SERVICE_1_CUSTOM_HOST)
        doReturn("MyTestHost")
                .`when`(repository).getHostnameForServiceId(TEST_SERVICE_ID_1)
        doReturn(listOf(announcementInfo))
                .`when`(repository).restartAnnouncingForHostname("MyTestHost")
        val inOrder = inOrder(prober, announcer)

        // Remove the custom host: the custom host's announcement is stopped and the probed services
        // which use that hostname are re-announced.
        advertiser.removeService(TEST_SERVICE_ID_1)

        inOrder.verify(prober).stop(TEST_SERVICE_ID_1)
        inOrder.verify(announcer).stop(TEST_SERVICE_ID_1)
        inOrder.verify(announcer).stop(TEST_SERVICE_ID_2)
        inOrder.verify(announcer).startSending(TEST_SERVICE_ID_2, announcementInfo, 0L /* initialDelayMs */)
    }

    @Test
    fun testAddMoreAddressesForCustomHost_restartAnnouncingForProbedServices() {
        val customHost = NsdServiceInfo().apply {
            hostname = "MyTestHost"
            hostAddresses = listOf(
                parseNumericAddress("192.0.2.23"),
                parseNumericAddress("2001:db8::1"))
        }
        doReturn("MyTestHost")
            .`when`(repository).getHostnameForServiceId(TEST_SERVICE_ID_1)
        doReturn("MyTestHost")
            .`when`(repository).getHostnameForServiceId(TEST_SERVICE_ID_2)
        val announcementInfo1 =
            addServiceAndFinishProbing(TEST_SERVICE_ID_1, TEST_SERVICE_1_CUSTOM_HOST)

        val probingInfo2 = addServiceAndStartProbing(TEST_SERVICE_ID_2, customHost)
        val announcementInfo2 = AnnouncementInfo(TEST_SERVICE_ID_2, emptyList(), emptyList())
        doReturn(announcementInfo2).`when`(repository).onProbingSucceeded(probingInfo2)
        doReturn(listOf(announcementInfo1, announcementInfo2))
            .`when`(repository).restartAnnouncingForHostname("MyTestHost")
        probeCb.onFinished(probingInfo2)

        val inOrder = inOrder(prober, announcer)

        inOrder.verify(announcer)
            .startSending(TEST_SERVICE_ID_2, announcementInfo2, 0L /* initialDelayMs */)
        inOrder.verify(announcer).stop(TEST_SERVICE_ID_1)
        inOrder.verify(announcer)
            .startSending(TEST_SERVICE_ID_1, announcementInfo1, 0L /* initialDelayMs */)
    }

    @Test
    fun testDoubleRemove() {
        addServiceAndFinishProbing(TEST_SERVICE_ID_1, TEST_SERVICE_1)

        val testExitInfo = mock(ExitAnnouncementInfo::class.java)
        doReturn(testExitInfo).`when`(repository).exitService(TEST_SERVICE_ID_1)
        advertiser.removeService(TEST_SERVICE_ID_1)

        verify(prober).stop(TEST_SERVICE_ID_1)
        verify(announcer).stop(TEST_SERVICE_ID_1)
        verify(announcer).startSending(TEST_SERVICE_ID_1, testExitInfo, EXIT_ANNOUNCEMENT_DELAY_MS)

        doReturn(false).`when`(repository).hasActiveService(TEST_SERVICE_ID_1)
        advertiser.removeService(TEST_SERVICE_ID_1)
        // Prober, announcer were still stopped only one time
        verify(prober, times(1)).stop(TEST_SERVICE_ID_1)
        verify(announcer, times(1)).stop(TEST_SERVICE_ID_1)
    }

    @Test
    fun testReplyToQuery() {
        addServiceAndFinishProbing(TEST_SERVICE_ID_1, TEST_SERVICE_1)

        val testReply = MdnsReplyInfo(emptyList(), emptyList(), 0, InetSocketAddress(0),
                InetSocketAddress(0), emptyList())
        doReturn(testReply).`when`(repository).getReply(any(), any())

        // Query obtained with:
        // scapy.raw(scapy.DNS(
        //  qd = scapy.DNSQR(qtype='PTR', qname='_testservice._tcp.local'))
        // ).hex().upper()
        val query = HexDump.hexStringToByteArray(
                "0000010000010000000000000C5F7465737473657276696365045F746370056C6F63616C00000C0001"
        )
        val src = InetSocketAddress(parseNumericAddress("2001:db8::456"), MdnsConstants.MDNS_PORT)
        packetHandler.handlePacket(query, query.size, src)

        val packetCaptor = ArgumentCaptor.forClass(MdnsPacket::class.java)
        val srcCaptor = ArgumentCaptor.forClass(InetSocketAddress::class.java)
        verify(repository).getReply(packetCaptor.capture(), srcCaptor.capture())

        assertEquals(src, srcCaptor.value)
        assertNotSame(src, srcCaptor.value, "src will be reused by the packetHandler, references " +
                "to it should not be used outside of handlePacket.")

        packetCaptor.value.let {
            assertEquals(1, it.questions.size)
            assertEquals(0, it.answers.size)
            assertEquals(0, it.authorityRecords.size)
            assertEquals(0, it.additionalRecords.size)

            assertTrue(it.questions[0] is MdnsPointerRecord)
            assertContentEquals(arrayOf("_testservice", "_tcp", "local"), it.questions[0].name)
        }

        verify(replySender).queueReply(testReply)
    }

    @Test
    fun testReplyToQuery_TruncatedBitSet() {
        addServiceAndFinishProbing(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        val src = InetSocketAddress(parseNumericAddress("2001:db8::456"), MdnsConstants.MDNS_PORT)
        val testReply = MdnsReplyInfo(emptyList(), emptyList(), 400L, InetSocketAddress(0), src,
                emptyList())
        val knownAnswersReply = MdnsReplyInfo(emptyList(), emptyList(), 400L, InetSocketAddress(0),
                src, emptyList())
        val knownAnswersReply2 = MdnsReplyInfo(emptyList(), emptyList(), 0L, InetSocketAddress(0),
                src, emptyList())
        doReturn(testReply).`when`(repository).getReply(
                argThat { pkg -> pkg.questions.size != 0 && pkg.answers.size == 0 &&
                        (pkg.flags and MdnsConstants.FLAG_TRUNCATED) != 0},
                eq(src))
        doReturn(knownAnswersReply).`when`(repository).getReply(
                argThat { pkg -> pkg.questions.size == 0 && pkg.answers.size != 0 &&
                        (pkg.flags and MdnsConstants.FLAG_TRUNCATED) != 0},
                eq(src))
        doReturn(knownAnswersReply2).`when`(repository).getReply(
                argThat { pkg -> pkg.questions.size == 0 && pkg.answers.size != 0 &&
                        (pkg.flags and MdnsConstants.FLAG_TRUNCATED) == 0},
                eq(src))

        // Query obtained with:
        // scapy.raw(scapy.DNS(
        //  tc = 1, qd = scapy.DNSQR(qtype='PTR', qname='_testservice._tcp.local'))
        // ).hex().upper()
        val query = HexDump.hexStringToByteArray(
                "0000030000010000000000000C5F7465737473657276696365045F746370056C6F63616C00000C0001"
        )

        packetHandler.handlePacket(query, query.size, src)

        val packetCaptor = ArgumentCaptor.forClass(MdnsPacket::class.java)
        verify(repository).getReply(packetCaptor.capture(), eq(src))

        packetCaptor.value.let {
            assertTrue((it.flags and MdnsConstants.FLAG_TRUNCATED) != 0)
            assertEquals(1, it.questions.size)
            assertEquals(0, it.answers.size)
            assertEquals(0, it.authorityRecords.size)
            assertEquals(0, it.additionalRecords.size)

            assertTrue(it.questions[0] is MdnsPointerRecord)
            assertContentEquals(arrayOf("_testservice", "_tcp", "local"), it.questions[0].name)
        }

        verify(replySender).queueReply(testReply)

        // Known-Answer packet with truncated bit set obtained with:
        // scapy.raw(scapy.DNS(
        //   tc = 1, qd = None, an = scapy.DNSRR(type='PTR', rrname='_testtype._tcp.local',
        //   rdata='othertestservice._testtype._tcp.local', rclass='IN', ttl=4500))
        // ).hex().upper()
        val knownAnswers = HexDump.hexStringToByteArray(
                "000003000000000100000000095F7465737474797065045F746370056C6F63616C00000C0001000" +
                        "011940027106F746865727465737473657276696365095F7465737474797065045F7463" +
                        "70056C6F63616C00"
        )

        packetHandler.handlePacket(knownAnswers, knownAnswers.size, src)

        verify(repository, times(2)).getReply(packetCaptor.capture(), eq(src))

        packetCaptor.value.let {
            assertTrue((it.flags and MdnsConstants.FLAG_TRUNCATED) != 0)
            assertEquals(0, it.questions.size)
            assertEquals(1, it.answers.size)
            assertEquals(0, it.authorityRecords.size)
            assertEquals(0, it.additionalRecords.size)

            assertTrue(it.answers[0] is MdnsPointerRecord)
            assertContentEquals(arrayOf("_testtype", "_tcp", "local"), it.answers[0].name)
        }

        verify(replySender).queueReply(knownAnswersReply)

        // Known-Answer packet obtained with:
        // scapy.raw(scapy.DNS(
        //   qd = None, an = scapy.DNSRR(type='PTR', rrname='_testtype._tcp.local',
        //   rdata='testservice._testtype._tcp.local', rclass='IN', ttl=4500))
        // ).hex().upper()
        val knownAnswers2 = HexDump.hexStringToByteArray(
                "000001000000000100000000095F7465737474797065045F746370056C6F63616C00000C0001000" +
                        "0119400220B7465737473657276696365095F7465737474797065045F746370056C6F63" +
                        "616C00"
        )

        packetHandler.handlePacket(knownAnswers2, knownAnswers2.size, src)

        verify(repository, times(3)).getReply(packetCaptor.capture(), eq(src))

        packetCaptor.value.let {
            assertTrue((it.flags and MdnsConstants.FLAG_TRUNCATED) == 0)
            assertEquals(0, it.questions.size)
            assertEquals(1, it.answers.size)
            assertEquals(0, it.authorityRecords.size)
            assertEquals(0, it.additionalRecords.size)

            assertTrue(it.answers[0] is MdnsPointerRecord)
            assertContentEquals(arrayOf("_testtype", "_tcp", "local"), it.answers[0].name)
        }

        verify(replySender).queueReply(knownAnswersReply2)
    }

    @Test
    fun testConflict() {
        addServiceAndFinishProbing(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        doReturn(mapOf(TEST_SERVICE_ID_1 to CONFLICT_SERVICE))
                .`when`(repository).getConflictingServices(any())

        // Reply obtained with:
        // scapy.raw(scapy.DNS(
        //    qd = None,
        //    an = scapy.DNSRR(type='TXT', rrname='_testservice._tcp.local'))
        // ).hex().upper()
        val query = HexDump.hexStringToByteArray("0000010000000001000000000C5F7465737473657276696" +
                "365045F746370056C6F63616C0000100001000000000000")
        val src = InetSocketAddress(parseNumericAddress("2001:db8::456"), MdnsConstants.MDNS_PORT)
        packetHandler.handlePacket(query, query.size, src)

        val packetCaptor = ArgumentCaptor.forClass(MdnsPacket::class.java)
        verify(repository).getConflictingServices(packetCaptor.capture())

        packetCaptor.value.let {
            assertEquals(0, it.questions.size)
            assertEquals(1, it.answers.size)
            assertEquals(0, it.authorityRecords.size)
            assertEquals(0, it.additionalRecords.size)

            assertTrue(it.answers[0] is MdnsTextRecord)
            assertContentEquals(arrayOf("_testservice", "_tcp", "local"), it.answers[0].name)
        }

        thread.waitForIdle(TIMEOUT_MS)
        verify(cb).onServiceConflict(advertiser, TEST_SERVICE_ID_1, CONFLICT_SERVICE)
    }

    @Test
    fun testRestartProbingForConflict() {
        val mockProbingInfo = mock(ProbingInfo::class.java)
        doReturn(mockProbingInfo).`when`(repository).setServiceProbing(TEST_SERVICE_ID_1)

        advertiser.maybeRestartProbingForConflict(TEST_SERVICE_ID_1)

        verify(prober).restartForConflict(mockProbingInfo)
    }

    @Test
    fun testRenameServiceForConflict() {
        val mockProbingInfo = mock(ProbingInfo::class.java)
        doReturn(mockProbingInfo).`when`(repository).renameServiceForConflict(
                TEST_SERVICE_ID_1, TEST_SERVICE_1)

        advertiser.renameServiceForConflict(TEST_SERVICE_ID_1, TEST_SERVICE_1)

        verify(prober).restartForConflict(mockProbingInfo)
    }

    @Test
    fun testReplaceExitingService() {
        doReturn(TEST_SERVICE_ID_DUPLICATE).`when`(repository)
                .addService(eq(TEST_SERVICE_ID_DUPLICATE), any(), any())
        advertiser.addService(TEST_SERVICE_ID_DUPLICATE, TEST_SERVICE_1_SUBTYPE,
                MdnsAdvertisingOptions.getDefaultOptions())
        verify(repository).addService(eq(TEST_SERVICE_ID_DUPLICATE), any(), any())
        verify(announcer).stop(TEST_SERVICE_ID_DUPLICATE)
        verify(prober).startProbing(any())
    }

    @Test
    fun testUpdateExistingService() {
        doReturn(TEST_SERVICE_ID_DUPLICATE).`when`(repository)
                .addService(eq(TEST_SERVICE_ID_DUPLICATE), any(), any())
        val subTypes = setOf("_sub")
        advertiser.updateService(TEST_SERVICE_ID_DUPLICATE, subTypes)
        verify(repository).updateService(eq(TEST_SERVICE_ID_DUPLICATE), any())
        verify(announcer, never()).stop(TEST_SERVICE_ID_DUPLICATE)
        verify(prober, never()).startProbing(any())
    }

    private fun addServiceAndStartProbing(serviceId: Int, serviceInfo: NsdServiceInfo):
            ProbingInfo {
        val testProbingInfo = mock(ProbingInfo::class.java)
        doReturn(serviceId).`when`(testProbingInfo).serviceId
        doReturn(testProbingInfo).`when`(repository).setServiceProbing(serviceId)

        advertiser.addService(serviceId, serviceInfo, MdnsAdvertisingOptions.getDefaultOptions())
        verify(repository).addService(serviceId, serviceInfo, null /* ttl */)
        verify(prober).startProbing(testProbingInfo)

        return testProbingInfo
    }

    private fun addServiceAndFinishProbing(serviceId: Int, serviceInfo: NsdServiceInfo):
            AnnouncementInfo {
        val testProbingInfo = addServiceAndStartProbing(serviceId, serviceInfo)

        // Simulate probing success: continues to announcing
        val testAnnouncementInfo = AnnouncementInfo(serviceId, emptyList(), emptyList())
        doReturn(testAnnouncementInfo).`when`(repository).onProbingSucceeded(testProbingInfo)
        probeCb.onFinished(testProbingInfo)
        return testAnnouncementInfo
    }
}
