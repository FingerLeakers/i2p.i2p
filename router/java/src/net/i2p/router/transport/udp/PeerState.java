package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.util.CoDelPriorityBlockingQueue;
import net.i2p.router.util.PriBlockingQueue;
import net.i2p.util.CachedIteratorArrayList;
import net.i2p.util.Log;
import net.i2p.util.ConcurrentHashSet;

/**
 * Contain all of the state about a UDP connection to a peer.
 * This is instantiated only after a connection is fully established.
 */
class PeerState {
    private final RouterContext _context;
    private final Log _log;
    /**
     * The peer are we talking to.  This should be set as soon as this
     * state is created if we are initiating a connection, but if we are
     * receiving the connection this will be set only after the connection
     * is established.
     */
    private final Hash _remotePeer;
    /** 
     * The AES key used to verify packets, set only after the connection is
     * established.  
     */
    private SessionKey _currentMACKey;
    /**
     * The AES key used to encrypt/decrypt packets, set only after the 
     * connection is established.
     */
    private SessionKey _currentCipherKey;
    /** 
     * The pending AES key for verifying packets if we are rekeying the 
     * connection, or null if we are not in the process of rekeying.
     */
    private SessionKey _nextMACKey;
    /** 
     * The pending AES key for encrypting/decrypting packets if we are 
     * rekeying the connection, or null if we are not in the process 
     * of rekeying.
     */
    private SessionKey _nextCipherKey;

    /**
     * The keying material used for the rekeying, or null if we are not in
     * the process of rekeying.
     */
    //private byte[] _nextKeyingMaterial;
    /** true if we began the current rekeying, false otherwise */
    //private boolean _rekeyBeganLocally;

    /** when were the current cipher and MAC keys established/rekeyed? */
    private long _keyEstablishedTime;

    /**
     *  How far off is the remote peer from our clock, in milliseconds?
     *  A positive number means our clock is ahead of theirs.
     */
    private long _clockSkew;
    private final Object _clockSkewLock = new Object();

    /** what is the current receive second, for congestion control? */
    private long _currentReceiveSecond;
    /** when did we last send them a packet? */
    private long _lastSendTime;
    /** when did we last send them a message that was ACKed */
    private long _lastSendFullyTime;
    /** when did we last send them a ping? */
    private long _lastPingTime;
    /** when did we last receive a packet from them? */
    private long _lastReceiveTime;
    /** how many consecutive messages have we sent and not received an ACK to */
    private int _consecutiveFailedSends;

    /** when did we last have a failed send (beginning of period) */
    // private long _lastFailedSendPeriod;

    /**
     *  Set of messageIds (Long) that we have received but not yet sent
     *  Since even with the smallest MTU we can fit 131 acks in a message,
     *  we are unlikely to get backed up on acks, so we don't keep
     *  them in any particular order.
     */
    private final Set<Long> _currentACKs;

    /** 
     * list of the most recent messageIds (Long) that we have received and sent
     * an ACK for.  We keep a few of these around to retransmit with _currentACKs,
     * hopefully saving some spurious retransmissions
     */
    private final Queue<ResendACK> _currentACKsResend;

    /** when did we last send ACKs to the peer? */
    private volatile long _lastACKSend;
    /** when did we decide we need to ACK to this peer? */
    private volatile long _wantACKSendSince;
    /** have we received a packet with the ECN bit set in the current second? */
    private boolean _currentSecondECNReceived;
    /** 
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    //private boolean _remoteWantsPreviousACKs;
    /** how many bytes should we send to the peer in a second */
    private int _sendWindowBytes;
    /** how many bytes can we send to the peer in the current second */
    private int _sendWindowBytesRemaining;
    private long _lastSendRefill;
    private int _sendBps;
    private int _sendBytes;
    private int _receiveBps;
    private int _receiveBytes;
    //private int _sendACKBps;
    //private int _sendZACKBytes;
    //private int _receiveACKBps;
    //private int _receiveACKBytes;
    private long _receivePeriodBegin;
    private volatile long _lastCongestionOccurred;
    /** 
     * when sendWindowBytes is below this, grow the window size quickly,
     * but after we reach it, grow it slowly
     *
     */
    private volatile int _slowStartThreshold;
    /** what IP is the peer sending and receiving packets on? */
    private final byte[] _remoteIP;
    /** cached IP address */
    private volatile InetAddress _remoteIPAddress;
    /** what port is the peer sending and receiving packets on? */
    private volatile int _remotePort;
    /** cached RemoteHostId, used to find the peerState by remote info */
    private volatile RemoteHostId _remoteHostId;

    /** if we need to contact them, do we need to talk to an introducer? */
    //private boolean _remoteRequiresIntroduction;

    /** 
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer 
     */
    private long _weRelayToThemAs;
    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     */
    private long _theyRelayToUsAs;
    /** what is the largest packet we can currently send to the peer? */
    private int _mtu;
    private int _mtuReceive;
    /** what is the largest packet we will ever send to the peer? */
    private int _largeMTU;
    /* how many consecutive packets at or under the min MTU have been received */
    private long _consecutiveSmall;
    /** when did we last check the MTU? */
    //private long _mtuLastChecked;
    private int _mtuIncreases;
    private int _mtuDecreases;
    /** current round trip time estimate */
    private int _rtt;
    /** smoothed mean deviation in the rtt */
    private int _rttDeviation;
    /** current retransmission timeout */
    private int _rto;
    
    /** how many packets will be considered within the retransmission rate calculation */
    static final long RETRANSMISSION_PERIOD_WIDTH = 100;
    
    private int _messagesReceived;
    private int _messagesSent;
    private int _packetsTransmitted;
    /** how many packets were retransmitted within the last RETRANSMISSION_PERIOD_WIDTH packets */
    private int _packetsRetransmitted;

    /** how many packets were transmitted within the last RETRANSMISSION_PERIOD_WIDTH packets */
    //private long _packetsPeriodTransmitted;
    //private int _packetsPeriodRetransmitted;
    //private int _packetRetransmissionRate;

    /** how many dup packets were received within the last RETRANSMISSION_PERIOD_WIDTH packets */
    private int _packetsReceivedDuplicate;
    private int _packetsReceived;
    
    /** list of InboundMessageState for active message */
    private final Map<Long, InboundMessageState> _inboundMessages;

    /**
     *  Mostly messages that have been transmitted and are awaiting acknowledgement,
     *  although there could be some that have not been sent yet.
     */
    private final List<OutboundMessageState> _outboundMessages;

    /**
     *  Priority queue of messages that have not yet been sent.
     *  They are taken from here and put in _outboundMessages.
     */
    //private final CoDelPriorityBlockingQueue<OutboundMessageState> _outboundQueue;
    private final PriBlockingQueue<OutboundMessageState> _outboundQueue;

    /** which outbound message is currently being retransmitted */
    private OutboundMessageState _retransmitter;
    
    private final UDPTransport _transport;
    
    /** have we migrated away from this peer to another newer one? */
    private volatile boolean _dead;

    /** Make sure a 4229 byte TunnelBuildMessage can be sent in one volley with small MTU */
    private static final int MIN_CONCURRENT_MSGS = 8;
    /** how many concurrent outbound messages do we allow throws OutboundMessageFragments to send */
    private int _concurrentMessagesAllowed = MIN_CONCURRENT_MSGS;
    /** 
     * how many outbound messages are currently being transmitted.  Not thread safe, as we're not strict
     */
    private int _concurrentMessagesActive;
    /** how many concurrency rejections have we had in a row */
    private int _consecutiveRejections;
    /** is it inbound? **/
    private final boolean _isInbound;
    /** Last time it was made an introducer **/
    private long _lastIntroducerTime;

    private static final int DEFAULT_SEND_WINDOW_BYTES = 8*1024;
    private static final int MINIMUM_WINDOW_BYTES = DEFAULT_SEND_WINDOW_BYTES;
    private static final int MAX_SEND_WINDOW_BYTES = 1024*1024;

    /** max number of msgs returned from allocateSend() */
    private static final int MAX_ALLOCATE_SEND = 2;

    /**
     *  Was 32 before 0.9.2, but since the streaming lib goes up to 128,
     *  we would just drop our own msgs right away during slow start.
     *  May need to adjust based on memory.
     */
    private static final int MAX_SEND_MSGS_PENDING = 128;

    /*
     * 596 gives us 588 IP byes, 568 UDP bytes, and with an SSU data message, 
     * 522 fragment bytes, which is enough to send a tunnel data message in 2 
     * packets. A tunnel data message sent over the wire is 1044 bytes, meaning 
     * we need 522 fragment bytes to fit it in 2 packets - add 46 for SSU, 20 
     * for UDP, and 8 for IP, giving us 596.  round up to mod 16, giving a total
     * of 608
     *
     * Well, we really need to count the acks as well, especially
     * 1 + (4 * MAX_RESEND_ACKS_SMALL) which can take up a significant amount of space.
     * We reduce the max acks when using the small MTU but it may not be enough...
     *
     * Goal: VTBM msg fragments 2646 / (620 - 87) fits nicely.
     *
     * Assuming that we can enforce an MTU correctly, this % 16 should be 12,
     * as the IP/UDP header is 28 bytes and data max should be mulitple of 16 for padding efficiency,
     * and so PacketBuilder.buildPacket() works correctly.
     */
    public static final int MIN_MTU = 620;

    /**
     * IPv6/UDP header is 48 bytes, so we want MTU % 16 == 0.
     */
    public static final int MIN_IPV6_MTU = 1280;
    public static final int MAX_IPV6_MTU = 1472;  // TODO 1488
    private static final int DEFAULT_MTU = MIN_MTU;

    /* 
     * based on measurements, 1350 fits nearly all reasonably small I2NP messages
     * (larger I2NP messages may be up to 1900B-4500B, which isn't going to fit
     * into a live network MTU anyway)
     *
     * TODO
     * VTBM is 2646, it would be nice to fit in two large
     * 2646 / 2 = 1323
     * 1323 + 74 + 46 + 1 + (4 * 9) = 1480
     * So why not make it 1492 (old ethernet is 1492, new is 1500)
     * Changed to 1492 in 0.8.9
     *
     * BUT through 0.8.11,
     * size estimate was bad, actual packet was up to 48 bytes bigger
     * To be figured out. Curse the ACKs.
     * Assuming that we can enforce an MTU correctly, this % 16 should be 12,
     * as the IP/UDP header is 28 bytes and data max should be mulitple of 16 for padding efficiency,
     * and so PacketBuilder.buildPacket() works correctly.
     */
    public static final int LARGE_MTU = 1484;
    
    private static final int MIN_RTO = 100 + ACKSender.ACK_FREQUENCY;
    private static final int INIT_RTO = 3*1000;
    public static final int INIT_RTT = INIT_RTO / 2;
    private static final int MAX_RTO = 15*1000;
    private static final int CLOCK_SKEW_FUDGE = (ACKSender.ACK_FREQUENCY * 2) / 3;
    
    /**
     *  The max number of acks we save to send as duplicates
     */
    private static final int MAX_RESEND_ACKS = 64;
    /**
     *  The max number of duplicate acks sent in each ack-only messge.
     *  Doesn't really matter, we have plenty of room...
     *  @since 0.7.13
     */
    private static final int MAX_RESEND_ACKS_LARGE = MAX_RESEND_ACKS / 3;
    /** for small MTU */
    private static final int MAX_RESEND_ACKS_SMALL = MAX_RESEND_ACKS / 5;

    private static final long RESEND_ACK_TIMEOUT = 5*60*1000;

    
    public PeerState(RouterContext ctx, UDPTransport transport,
                     byte[] remoteIP, int remotePort, Hash remotePeer, boolean isInbound) {
        _context = ctx;
        _log = ctx.logManager().getLog(PeerState.class);
        _transport = transport;
        long now = ctx.clock().now();
        _keyEstablishedTime = now;
        _currentReceiveSecond = now - (now % 1000);
        _lastSendTime = now;
        _lastReceiveTime = now;
        _currentACKs = new ConcurrentHashSet<Long>();
        _currentACKsResend = new LinkedBlockingQueue<ResendACK>();
        _sendWindowBytes = DEFAULT_SEND_WINDOW_BYTES;
        _sendWindowBytesRemaining = DEFAULT_SEND_WINDOW_BYTES;
        _slowStartThreshold = MAX_SEND_WINDOW_BYTES/2;
        _lastSendRefill = now;
        _receivePeriodBegin = now;
        _lastCongestionOccurred = -1;
        _remotePort = remotePort;
        if (remoteIP.length == 4) {
            _mtu = DEFAULT_MTU;
            _mtuReceive = DEFAULT_MTU;
            _largeMTU = transport.getMTU(false);
        } else {
            _mtu = MIN_IPV6_MTU;
            _mtuReceive = MIN_IPV6_MTU;
            _largeMTU = transport.getMTU(true);
        }
        //_mtuLastChecked = -1;
        _lastACKSend = -1;
        _rto = INIT_RTO;
        _rtt = INIT_RTT;
        _rttDeviation = _rtt;
        _inboundMessages = new HashMap<Long, InboundMessageState>(8);
        _outboundMessages = new CachedIteratorArrayList<OutboundMessageState>(32);
        //_outboundQueue = new CoDelPriorityBlockingQueue(ctx, "UDP-PeerState", 32);
        _outboundQueue = new PriBlockingQueue<OutboundMessageState>(ctx, "UDP-PeerState", 32);
        // all createRateStat() moved to EstablishmentManager
        _remoteIP = remoteIP;
        _remotePeer = remotePeer;
        _isInbound = isInbound;
        _remoteHostId = new RemoteHostId(remoteIP, remotePort);
    }
    
    /** 
     *  Caller should sync; UDPTransport must remove and add to peersByRemoteHost map
     *  @since 0.9.3
     */
    public void changePort(int newPort) {
        if (newPort != _remotePort) {
            _remoteHostId = new RemoteHostId(_remoteIP, newPort);
            _remotePort = newPort;
        }
    }

    /**
     * The peer are we talking to.  This should be set as soon as this
     * state is created if we are initiating a connection, but if we are
     * receiving the connection this will be set only after the connection
     * is established.
     */
    public Hash getRemotePeer() { return _remotePeer; }
    /** 
     * The AES key used to verify packets, set only after the connection is
     * established.  
     */
    public SessionKey getCurrentMACKey() { return _currentMACKey; }
    /**
     * The AES key used to encrypt/decrypt packets, set only after the 
     * connection is established.
     */
    public SessionKey getCurrentCipherKey() { return _currentCipherKey; }

    /** 
     * The pending AES key for verifying packets if we are rekeying the 
     * connection, or null if we are not in the process of rekeying.
     *
     * @return null always, rekeying unimplemented
     */
    public SessionKey getNextMACKey() { return _nextMACKey; }

    /** 
     * The pending AES key for encrypting/decrypting packets if we are 
     * rekeying the connection, or null if we are not in the process 
     * of rekeying.
     *
     * @return null always, rekeying unimplemented
     */
    public SessionKey getNextCipherKey() { return _nextCipherKey; }

    /**
     * The keying material used for the rekeying, or null if we are not in
     * the process of rekeying.
     * deprecated unused
     */
    //public byte[] getNextKeyingMaterial() { return _nextKeyingMaterial; }

    /** true if we began the current rekeying, false otherwise */
    //public boolean getRekeyBeganLocally() { return _rekeyBeganLocally; }

    /** when were the current cipher and MAC keys established/rekeyed? */
    public long getKeyEstablishedTime() { return _keyEstablishedTime; }

    /**
     *  How far off is the remote peer from our clock, in milliseconds?
     *  A positive number means our clock is ahead of theirs.
     */
    public long getClockSkew() { return _clockSkew ; }

    /** what is the current receive second, for congestion control? */
    public long getCurrentReceiveSecond() { return _currentReceiveSecond; }
    /** when did we last send them a packet? */
    public long getLastSendTime() { return _lastSendTime; }
    /** when did we last send them a message that was ACKed? */
    public long getLastSendFullyTime() { return _lastSendFullyTime; }
    /** when did we last receive a packet from them? */
    public long getLastReceiveTime() { return _lastReceiveTime; }
    /** how many seconds have we sent packets without any ACKs received? */
    public int getConsecutiveFailedSends() { return _consecutiveFailedSends; }

    /**
     *  have we received a packet with the ECN bit set in the current second?
     *  @return false always
     *  @deprecated unused, ECNs are never sent, always returns false
     */
    public boolean getCurrentSecondECNReceived() { return _currentSecondECNReceived; }

    /** 
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    //public boolean getRemoteWantsPreviousACKs() { return _remoteWantsPreviousACKs; }

    /** how many bytes should we send to the peer in a second */
    public int getSendWindowBytes() {
        synchronized(_outboundMessages) {
            return _sendWindowBytes;
        }
    }

    /** how many bytes can we send to the peer in the current second */
    public int getSendWindowBytesRemaining() {
        synchronized(_outboundMessages) {
            return _sendWindowBytesRemaining;
        }
    }

    /** what IP is the peer sending and receiving packets on? */
    public byte[] getRemoteIP() { return _remoteIP; }

    /**
     *  @return may be null if IP is invalid
     */
    public InetAddress getRemoteIPAddress() {
        if (_remoteIPAddress == null) {
            try {
                _remoteIPAddress = InetAddress.getByAddress(_remoteIP);
            } catch (UnknownHostException uhe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Invalid IP? ", uhe);
            }
        }
        return _remoteIPAddress;
    }

    /** what port is the peer sending and receiving packets on? */
    public int getRemotePort() { return _remotePort; }

    /** if we need to contact them, do we need to talk to an introducer? */
    //public boolean getRemoteRequiresIntroduction() { return _remoteRequiresIntroduction; }

    /** 
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer 
     * @return 0 (no relay) if unset previously
     */
    public long getWeRelayToThemAs() { return _weRelayToThemAs; }

    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     * @return 0 (no relay) if unset previously
     */
    public long getTheyRelayToUsAs() { return _theyRelayToUsAs; }

    /** what is the largest packet we can send to the peer? */
    public int getMTU() { return _mtu; }

    /**
     *  Estimate how large the other side's MTU is.
     *  This could be wrong.
     *  It is used only for the HTML status.
     */
    public int getReceiveMTU() { return _mtuReceive; }

    /** when did we last check the MTU? */
  /****
    public long getMTULastChecked() { return _mtuLastChecked; }
    public long getMTUIncreases() { return _mtuIncreases; }
    public long getMTUDecreases() { return _mtuDecreases; }
  ****/
    
    /** 
     * The AES key used to verify packets, set only after the connection is
     * established.  
     */
    public void setCurrentMACKey(SessionKey key) { _currentMACKey = key; }

    /**
     * The AES key used to encrypt/decrypt packets, set only after the 
     * connection is established.
     */
    public void setCurrentCipherKey(SessionKey key) { _currentCipherKey = key; }

    /** 
     * The pending AES key for verifying packets if we are rekeying the 
     * connection, or null if we are not in the process of rekeying.
     * @deprecated unused
     */
    public void setNextMACKey(SessionKey key) { _nextMACKey = key; }

    /** 
     * The pending AES key for encrypting/decrypting packets if we are 
     * rekeying the connection, or null if we are not in the process 
     * of rekeying.
     * @deprecated unused
     */
    public void setNextCipherKey(SessionKey key) { _nextCipherKey = key; }

    /**
     * The keying material used for the rekeying, or null if we are not in
     * the process of rekeying.
     * @deprecated unused
     */
    //public void setNextKeyingMaterial(byte data[]) { _nextKeyingMaterial = data; }

    /**
     * @param local true if we began the current rekeying, false otherwise
     * @deprecated unused
     */
    //public void setRekeyBeganLocally(boolean local) { _rekeyBeganLocally = local; }

    /**
     * when were the current cipher and MAC keys established/rekeyed?
     * @deprecated unused
     */
    public void setKeyEstablishedTime(long when) { _keyEstablishedTime = when; }

    /**
     *  Update the moving-average clock skew based on the current difference.
     *  The raw skew will be adjusted for RTT/2 here.
     *  A positive number means our clock is ahead of theirs.
     *  @param skew milliseconds, NOT adjusted for RTT.
     */
    public void adjustClockSkew(long skew) { 
        // the real one-way delay is much less than RTT / 2, due to ack delays,
        // so add a fudge factor
        long actualSkew = skew + CLOCK_SKEW_FUDGE - (_rtt / 2); 
        //_log.error("Skew " + skew + " actualSkew " + actualSkew + " rtt " + _rtt + " pktsRcvd " + _packetsReceived);
        // First time...
        // This is important because we need accurate
        // skews right from the beginning, since the median is taken
        // and fed to the timestamper. Lots of connections only send a few packets.
        if (_packetsReceived <= 1) {
            synchronized(_clockSkewLock) {
                _clockSkew = actualSkew; 
            }
            return;
        }
        double adj = 0.1 * actualSkew; 
        synchronized(_clockSkewLock) {
            _clockSkew = (long) (0.9*_clockSkew + adj); 
        }
    }

    /** what is the current receive second, for congestion control? */
    public void setCurrentReceiveSecond(long sec) { _currentReceiveSecond = sec; }
    /** when did we last send them a packet? */
    public void setLastSendTime(long when) { _lastSendTime = when; }
    /** when did we last receive a packet from them? */
    public void setLastReceiveTime(long when) { _lastReceiveTime = when; }

    /**
     *  Note ping sent. Does not update last send time.
     *  @since 0.9.3
     */
    public void setLastPingTime(long when) { _lastPingTime = when; }

    /**
     *  Latest of last sent, last ACK, last ping
     *  @since 0.9.3
     */
    public long getLastSendOrPingTime() {
        return Math.max(Math.max(_lastSendTime, _lastACKSend), _lastPingTime);
    }

    /** return the smoothed send transfer rate */
    public int getSendBps() { return _sendBps; }
    public synchronized int getReceiveBps() { return _receiveBps; }

    public int incrementConsecutiveFailedSends() { 
        synchronized(_outboundMessages) {
            _concurrentMessagesActive--;
            if (_concurrentMessagesActive < 0)
                _concurrentMessagesActive = 0;
            
            //long now = _context.clock().now()/(10*1000);
            //if (_lastFailedSendPeriod >= now) {
            //    // ignore... too fast
            //} else {
            //    _lastFailedSendPeriod = now;
                _consecutiveFailedSends++; 
            //}
            return _consecutiveFailedSends;
        }
    }

    public long getInactivityTime() {
        long now = _context.clock().now();
        long lastActivity = Math.max(_lastReceiveTime, _lastSendFullyTime);
        return now - lastActivity;
    }
    
    /** how fast we are sending *ack* packets */
    //public int getSendACKBps() { return _sendACKBps; }
    //public int getReceiveACKBps() { return _receiveACKBps; }
    
    /** 
     * have all of the packets received in the current second requested that
     * the previous second's ACKs be sent?
     */
    //public void remoteDoesNotWantPreviousACKs() { _remoteWantsPreviousACKs = false; }
    
    /** should we ignore the peer state's congestion window, and let anything through? */
    private static final boolean IGNORE_CWIN = false;
    /** should we ignore the congestion window on the first push of every message? */
    private static final boolean ALWAYS_ALLOW_FIRST_PUSH = false;
    
    /** 
     * Decrement the remaining bytes in the current period's window,
     * returning true if the full size can be decremented, false if it
     * cannot.  If it is not decremented, the window size remaining is 
     * not adjusted at all.
     *
     *  Caller should synch
     */
    private boolean allocateSendingBytes(int size, int messagePushCount) { return allocateSendingBytes(size, false, messagePushCount); }

    //private boolean allocateSendingBytes(int size, boolean isForACK) { return allocateSendingBytes(size, isForACK, -1); }

    /**
     *  Caller should synch
     */
    private boolean allocateSendingBytes(int size, boolean isForACK, int messagePushCount) { 
        long now = _context.clock().now();
        long duration = now - _lastSendRefill;
        if (duration >= 1000) {
            _sendWindowBytesRemaining = _sendWindowBytes;
            _sendBytes += size;
            _sendBps = (int)(0.9f*_sendBps + 0.1f*(_sendBytes * (1000f/duration)));
            //if (isForACK) {
            //    _sendACKBytes += size;
            //    _sendACKBps = (int)(0.9f*(float)_sendACKBps + 0.1f*((float)_sendACKBytes * (1000f/(float)duration)));
            //}
            _sendBytes = 0;
            //_sendACKBytes = 0;
            _lastSendRefill = now;
        }
        //if (true) return true;
        if (IGNORE_CWIN || size <= _sendWindowBytesRemaining || (ALWAYS_ALLOW_FIRST_PUSH && messagePushCount == 0)) {
            if ( (messagePushCount == 0) && (_concurrentMessagesActive > _concurrentMessagesAllowed) ) {
                _consecutiveRejections++;
                _context.statManager().addRateData("udp.rejectConcurrentActive", _concurrentMessagesActive, _consecutiveRejections);
                return false;
            } else if (messagePushCount == 0) {
                _context.statManager().addRateData("udp.allowConcurrentActive", _concurrentMessagesActive, _concurrentMessagesAllowed);
                _concurrentMessagesActive++;
                if (_consecutiveRejections > 0) 
                    _context.statManager().addRateData("udp.rejectConcurrentSequence", _consecutiveRejections, _concurrentMessagesActive);
                _consecutiveRejections = 0;
            }
            _sendWindowBytesRemaining -= size; 
            _sendBytes += size;
            _lastSendTime = now;
            //if (isForACK) 
            //    _sendACKBytes += size;
            return true;
        } else {
            return false;
        }
    }
    
    /** if we need to contact them, do we need to talk to an introducer? */
    //public void setRemoteRequiresIntroduction(boolean required) { _remoteRequiresIntroduction = required; }

    /** 
     * if we are serving as an introducer to them, this is the the tag that
     * they can publish that, when presented to us, will cause us to send
     * a relay introduction to the current peer 
     * @param tag 1 to Integer.MAX_VALUE, or 0 if relaying disabled
     */
    public void setWeRelayToThemAs(long tag) { _weRelayToThemAs = tag; }

    /**
     * If they have offered to serve as an introducer to us, this is the tag
     * we can use to publish that fact.
     * @param tag 1 to Integer.MAX_VALUE, or 0 if relaying disabled
     */
    public void setTheyRelayToUsAs(long tag) { _theyRelayToUsAs = tag; }

    /** what is the largest packet we can send to the peer? */
  /****
    public void setMTU(int mtu) { 
        _mtu = mtu; 
        _mtuLastChecked = _context.clock().now();
    }
  ****/

    public int getSlowStartThreshold() { return _slowStartThreshold; }

    public int getConcurrentSends() {
        synchronized(_outboundMessages) {
            return _concurrentMessagesActive;
        }
    }

    public int getConcurrentSendWindow() {
        synchronized(_outboundMessages) {
            return _concurrentMessagesAllowed;
        }
    }

    public int getConsecutiveSendRejections() {
        synchronized(_outboundMessages) {
            return _consecutiveRejections;
        }
    }

    public boolean isInbound() { return _isInbound; }

    /** @since IPv6 */
    public boolean isIPv6() {
        return _remoteIP.length == 16;
    }

    public long getIntroducerTime() { return _lastIntroducerTime; }
    public void setIntroducerTime() { _lastIntroducerTime = _context.clock().now(); }
    
    /** we received the message specified completely */
    public void messageFullyReceived(Long messageId, int bytes) { messageFullyReceived(messageId, bytes, false); }

    public synchronized void messageFullyReceived(Long messageId, int bytes, boolean isForACK) {
        if (bytes > 0) {
            _receiveBytes += bytes;
            //if (isForACK)
            //    _receiveACKBytes += bytes;
        } else {
            //if (true || _retransmissionPeriodStart + 1000 < _context.clock().now()) {
                _packetsReceivedDuplicate++;
            //} else {
            //    _retransmissionPeriodStart = _context.clock().now();
            //    _packetsReceivedDuplicate = 1;
            //}
        }
        
        long now = _context.clock().now();
        long duration = now - _receivePeriodBegin;
        if (duration >= 1000) {
            _receiveBps = (int)(0.9f*_receiveBps + 0.1f*(_receiveBytes * (1000f/duration)));
            //if (isForACK)
            //    _receiveACKBps = (int)(0.9f*(float)_receiveACKBps + 0.1f*((float)_receiveACKBytes * (1000f/(float)duration)));
            //_receiveACKBytes = 0;
            _receiveBytes = 0;
            _receivePeriodBegin = now;
           _context.statManager().addRateData("udp.receiveBps", _receiveBps);
        }
        
        if (_wantACKSendSince <= 0)
            _wantACKSendSince = now;
        _currentACKs.add(messageId);
        _messagesReceived++;
    }
    
    public void messagePartiallyReceived() {
        if (_wantACKSendSince <= 0)
            _wantACKSendSince = _context.clock().now();
    }
    
    /** 
     * Fetch the internal id (Long) to InboundMessageState for incomplete inbound messages.
     * Access to this map must be synchronized explicitly!
     */
    public Map<Long, InboundMessageState> getInboundMessages() { return _inboundMessages; }

    /**
     * Expire partially received inbound messages, returning how many are still pending.
     * This should probably be fired periodically, in case a peer goes silent and we don't
     * try to send them any messages (and don't receive any messages from them either)
     *
     */
    public int expireInboundMessages() { 
        int rv = 0;
        
        synchronized (_inboundMessages) {
            for (Iterator<InboundMessageState> iter = _inboundMessages.values().iterator(); iter.hasNext(); ) {
                InboundMessageState state = iter.next();
                if (state.isExpired() || _dead) {
                    iter.remove();
                    // state.releaseResources() ??
                } else {
                    if (state.isComplete()) {
                        _log.error("inbound message is complete, but wasn't handled inline? " + state + " with " + this);
                        iter.remove();
                        // state.releaseResources() ??
                    } else {
                        rv++;
                    }
                }
            }
        }
        return rv;
    }
    
    /** 
     * either they told us to back off, or we had to resend to get 
     * the data through.  
     *  Caller should synch on this
     *  @return true if window shrunk, but nobody uses the return value
     */
    private boolean congestionOccurred() {
        long now = _context.clock().now();
        if (_lastCongestionOccurred + _rto > now)
            return false; // only shrink once every few seconds
        _lastCongestionOccurred = now;
        
        int congestionAt = _sendWindowBytes;
        //if (true)
        //    _sendWindowBytes -= 10000;
        //else
            _sendWindowBytes = _sendWindowBytes/2; //(_sendWindowBytes*2) / 3;
        if (_sendWindowBytes < MINIMUM_WINDOW_BYTES)
            _sendWindowBytes = MINIMUM_WINDOW_BYTES;
        //if (congestionAt/2 < _slowStartThreshold)
            _slowStartThreshold = congestionAt/2;
        return true;
    }
    
    /**
     * Grab a list of message ids (Long) that we want to send to the remote
     * peer, regardless of the packet size, but don't remove it from our 
     * "want to send" list.  If the message id is transmitted to the peer,
     * removeACKMessage(Long) should be called.
     *
     * The returned list contains acks not yet sent only.
     * The caller should NOT transmit all of them all the time,
     * even if there is room,
     * or the packets will have way too much overhead.
     *
     * @return a new list, do as you like with it
     */
    public List<Long> getCurrentFullACKs() {
            // no such element exception seen here
            List<Long> rv = new ArrayList<Long>(_currentACKs);
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Returning " + _currentACKs.size() + " current acks");
            return rv;
    }

    /**
     * Grab a list of message ids (Long) that we want to send to the remote
     * peer, regardless of the packet size, but don't remove it from our 
     * "want to send" list.
     *
     * The returned list contains
     * a random assortment of acks already sent.
     * The caller should NOT transmit all of them all the time,
     * even if there is room,
     * or the packets will have way too much overhead.
     *
     * @return a new list, do as you like with it
     * @since 0.8.12 was included in getCurrentFullACKs()
     */
    public List<Long> getCurrentResendACKs() {
            int sz = _currentACKsResend.size();
            List<Long> randomResends = new ArrayList<Long>(sz);
            if (sz > 0) {
                long cutoff = _context.clock().now() - RESEND_ACK_TIMEOUT;
                int i = 0;
                for (Iterator<ResendACK> iter = _currentACKsResend.iterator(); iter.hasNext(); ) {
                    ResendACK rack  = iter.next();
                    if (rack.time > cutoff && i++ < MAX_RESEND_ACKS) {
                        randomResends.add(rack.id);
                    } else {
                        iter.remove();
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Expired ack " + rack.id + " sent " + (cutoff + RESEND_ACK_TIMEOUT - rack.time) +
                                      " ago, now " + i + " resend acks");
                    }
                }
                if (i > 1)
                    Collections.shuffle(randomResends, _context.random());
            }
            return randomResends;
    }

    /**
     * The ack was sent.
     * Side effect - sets _lastACKSend
     */
    public void removeACKMessage(Long messageId) {
            boolean removed = _currentACKs.remove(messageId);
            if (removed) {
                // only add if removed from current, as this may be called for
                // acks already in _currentACKsResend.
                _currentACKsResend.offer(new ResendACK(messageId, _context.clock().now()));
                // trim happens in getCurrentResendACKs above
                if (_log.shouldLog(Log.INFO))
                    _log.info("Sent ack " + messageId + " now " + _currentACKs.size() + " current and " +
                              _currentACKsResend.size() + " resend acks");
            }
            // should we only do this if removed?
            _lastACKSend = _context.clock().now();
    }
    
    /** 
     * grab a list of ACKBitfield instances, some of which may fully 
     * ACK a message while others may only partially ACK a message.  
     * the values returned are limited in size so that they will fit within
     * the peer's current MTU as an ACK - as such, not all messages may be
     * ACKed with this call.  Be sure to check getWantedACKSendSince() which
     * will be unchanged if there are ACKs remaining.
     *
     * @return non-null, possibly empty
     * @deprecated unused
     */
    public List<ACKBitfield> retrieveACKBitfields() { return retrieveACKBitfields(true); }

    /**
     * See above. Only called by ACKSender with alwaysIncludeRetransmissions = false.
     * So this is only for ACK-only packets, so all the size limiting is useless.
     * FIXME.
     * Side effect - sets _lastACKSend if rv is non-empty
     *
     * @return non-null, possibly empty
     */
    public List<ACKBitfield> retrieveACKBitfields(boolean alwaysIncludeRetransmissions) {
        int bytesRemaining = countMaxACKData();

            // Limit the overhead of all the resent acks when using small MTU
            // 64 bytes in a 608-byte packet is too much...
            // Send a random subset of all the queued resend acks.
            int resendSize = _currentACKsResend.size();
            int maxResendAcks;
            if (bytesRemaining < MIN_MTU)
                maxResendAcks = MAX_RESEND_ACKS_SMALL;
            else
                maxResendAcks = MAX_RESEND_ACKS_LARGE;
            List<ACKBitfield> rv = new ArrayList<ACKBitfield>(maxResendAcks);

            // save to add to currentACKsResend later so we don't include twice
            List<Long> currentACKsRemoved = new ArrayList<Long>(_currentACKs.size());
            // As explained above, we include the acks in any order
            // since we are unlikely to get backed up -
            // just take them using the Set iterator.
            Iterator<Long> iter = _currentACKs.iterator();
            while (bytesRemaining >= 4 && iter.hasNext()) {
                Long val = iter.next();
                iter.remove();
                long id = val.longValue();
                rv.add(new FullACKBitfield(id));
                currentACKsRemoved.add(val);
                bytesRemaining -= 4;
            }
            if (_currentACKs.isEmpty())
                _wantACKSendSince = -1;
            if (alwaysIncludeRetransmissions || !rv.isEmpty()) {
                List<Long> randomResends = getCurrentResendACKs();
                // now repeat by putting in some old ACKs
                // randomly selected from the Resend queue.
                // Maybe we should only resend each one a certain number of times...
                int oldIndex = Math.min(resendSize, maxResendAcks);
                iter = randomResends.iterator();
                while (bytesRemaining >= 4 && oldIndex-- > 0 && iter.hasNext()) {
                    Long cur = iter.next();
                    long c = cur.longValue();
                    FullACKBitfield bf = new FullACKBitfield(c);
                    // try to avoid duplicates ??
                    // ACKsResend is not checked for dups at add time
                    //if (rv.contains(bf)) {
                    //    iter.remove();
                    //} else {
                        rv.add(bf);
                        bytesRemaining -= 4;
                    //}
                }
                if (!currentACKsRemoved.isEmpty()) {
                    long now = _context.clock().now();
                    for (Long val : currentACKsRemoved) {
                        _currentACKsResend.offer(new ResendACK(val, now));
                    }
                    // trim happens in getCurrentResendACKs above
                }
            }





        int partialIncluded = 0;
        if (bytesRemaining > 4) {
            // ok, there's room to *try* to fit in some partial ACKs, so
            // we should try to find some packets to partially ACK 
            // (preferably the ones which have the most received fragments)
            List<ACKBitfield> partial = new ArrayList<ACKBitfield>();
            fetchPartialACKs(partial);
            // we may not be able to use them all, but lets try...
            for (int i = 0; (bytesRemaining > 4) && (i < partial.size()); i++) {
                ACKBitfield bitfield = partial.get(i);
                int bytes = (bitfield.fragmentCount() / 7) + 1;
                if (bytesRemaining > bytes + 4) { // msgId + bitfields
                    rv.add(bitfield);
                    bytesRemaining -= bytes + 4;
                    partialIncluded++;
                } else {
                    // continue on to another partial, in case there's a 
                    // smaller one that will fit
                }
            }
        }

        if (!rv.isEmpty())
            _lastACKSend = _context.clock().now();
        //if (rv == null)
        //    rv = Collections.EMPTY_LIST;
        if (partialIncluded > 0)
            _context.statManager().addRateData("udp.sendACKPartial", partialIncluded, rv.size() - partialIncluded);
        return rv;
    }
    
    /**
     *  @param rv out parameter, populated with true partial ACKBitfields.
     *            no full bitfields are included.
     */
    void fetchPartialACKs(List<ACKBitfield> rv) {
        List<InboundMessageState> states = null;
        int curState = 0;
        synchronized (_inboundMessages) {
            int numMessages = _inboundMessages.size();
            if (numMessages <= 0) 
                return;
            // todo: make this a list instead of a map, so we can iterate faster w/out the memory overhead?
            for (Iterator<InboundMessageState> iter = _inboundMessages.values().iterator(); iter.hasNext(); ) {
                InboundMessageState state = iter.next();
                if (state.isExpired()) {
                    //if (_context instanceof RouterContext)
                    //    ((RouterContext)_context).messageHistory().droppedInboundMessage(state.getMessageId(), state.getFrom(), "expired partially received: " + state.toString());
                    iter.remove();
                    // state.releaseResources() ??
                } else {
                    if (!state.isComplete()) {
                        if (states == null)
                            states = new ArrayList<InboundMessageState>(numMessages);
                        states.add(state);
                    }
                }
            }
        }
        if (states != null) {
            for (InboundMessageState ims : states) {
                ACKBitfield abf = ims.createACKBitfield();
                if (!abf.receivedComplete())
                    rv.add(abf);
            }
        }
    }
    
    /**
     *  A dummy "partial" ack which represents a full ACK of a message
     */
    private static class FullACKBitfield implements ACKBitfield {
        private final long _msgId;

        public FullACKBitfield(long id) { _msgId = id; }

        public int fragmentCount() { return 1; }
        public int ackCount() { return 1; }
        public int highestReceived() { return 0; }
        public long getMessageId() { return _msgId; }
        public boolean received(int fragmentNum) { return true; }
        public boolean receivedComplete() { return true; }
        @Override
        public int hashCode() { return (int) _msgId; }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FullACKBitfield)) return false;
            return _msgId == ((ACKBitfield)o).getMessageId();
        }
        @Override
        public String toString() { return "Full ACK " + _msgId; }
    }
        
    /**
     *  We sent a message which was ACKed containing the given # of bytes.
     *  Caller should synch on this
     */
    private void locked_messageACKed(int bytesACKed, long lifetime, int numSends) {
        _concurrentMessagesActive--;
        if (_concurrentMessagesActive < 0)
            _concurrentMessagesActive = 0;
        
        _consecutiveFailedSends = 0;
        // _lastFailedSendPeriod = -1;
        if (numSends < 2) {
            if (_context.random().nextInt(_concurrentMessagesAllowed) <= 0)
                _concurrentMessagesAllowed++;
            
            if (_sendWindowBytes <= _slowStartThreshold) {
                _sendWindowBytes += bytesACKed;
            } else {
                //if (false) {
                //    _sendWindowBytes += 16; // why 16?
                //} else {
                    float prob = ((float)bytesACKed) / ((float)(_sendWindowBytes<<1));
                    float v = _context.random().nextFloat();
                    if (v < 0) v = 0-v;
                    if (v <= prob)
                        _sendWindowBytes += bytesACKed; //512; // bytesACKed;
                //}
            }
        } else {
            int allow = _concurrentMessagesAllowed - 1;
            if (allow < MIN_CONCURRENT_MSGS)
                allow = MIN_CONCURRENT_MSGS;
            _concurrentMessagesAllowed = allow;
        }
        if (_sendWindowBytes > MAX_SEND_WINDOW_BYTES)
            _sendWindowBytes = MAX_SEND_WINDOW_BYTES;
        _lastReceiveTime = _context.clock().now();
        _lastSendFullyTime = _lastReceiveTime;
        
        //if (true) {
            if (_sendWindowBytesRemaining + bytesACKed <= _sendWindowBytes)
                _sendWindowBytesRemaining += bytesACKed;
            else
                _sendWindowBytesRemaining = _sendWindowBytes;
        //}
        
        _messagesSent++;
        if (numSends < 2) {
            // caller synchs
            //synchronized (this) {
                recalculateTimeouts(lifetime);
                adjustMTU();
            //}
        }
    }

    /**
     *  We sent a message which was ACKed containing the given # of bytes.
     */
    private void messageACKed(int bytesACKed, long lifetime, int numSends) {
        synchronized(this) {
            locked_messageACKed(bytesACKed, lifetime, numSends);
        }
        if (numSends >= 2 && _log.shouldLog(Log.INFO))
            _log.info("acked after numSends=" + numSends + " w/ lifetime=" + lifetime + " and size=" + bytesACKed);
        
        _context.statManager().addRateData("udp.sendBps", _sendBps);
    }

    /** This is the value specified in RFC 2988 */
    private static final float RTT_DAMPENING = 0.125f;
    
    /**
     *  Adjust the tcp-esque timeouts.
     *  Caller should synch on this
     */
    private void recalculateTimeouts(long lifetime) {
        // the rttDev calculation matches that recommended in RFC 2988 (beta = 1/4)
        _rttDeviation = _rttDeviation + (int)(0.25d*(Math.abs(lifetime-_rtt)-_rttDeviation));
        
        float scale = RTT_DAMPENING;
        // the faster we are going, the slower we want to reduce the rtt
        //if (_sendBps > 0)
        //    scale = lifetime / ((float)lifetime + (float)_sendBps);
        //if (scale < 0.001f) scale = 0.001f;
        
        _rtt = (int)(_rtt*(1.0f-scale) + (scale)*lifetime);
        // K = 4
        _rto = Math.min(MAX_RTO, Math.max(minRTO(), _rtt + (_rttDeviation<<2)));
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Recalculating timeouts w/ lifetime=" + lifetime + ": rtt=" + _rtt
        //               + " rttDev=" + _rttDeviation + " rto=" + _rto);
    }
    
    /**
     *  Caller should synch on this
     */
    private void adjustMTU() {
        double retransPct = 0;
        if (_packetsTransmitted > 10) {
            retransPct = (double)_packetsRetransmitted/(double)_packetsTransmitted;
            boolean wantLarge = retransPct < .30d; // heuristic to allow fairly lossy links to use large MTUs
            if (wantLarge && _mtu != _largeMTU) {
                if (_context.random().nextLong(_mtuDecreases) <= 0) {
                    _mtu = _largeMTU;
                    _mtuIncreases++;
                    _context.statManager().addRateData("udp.mtuIncrease", _mtuIncreases);
		}
	    } else if (!wantLarge && _mtu == _largeMTU) {
                _mtu = _remoteIP.length == 4 ? MIN_MTU : MIN_IPV6_MTU;
                _mtuDecreases++;
                _context.statManager().addRateData("udp.mtuDecrease", _mtuDecreases);
	    }
        } else {
            _mtu = _remoteIP.length == 4 ? DEFAULT_MTU : MIN_IPV6_MTU;
        }
    }

    /**
     *  @since 0.9.2
     */
    public synchronized void setHisMTU(int mtu) {
        if (mtu <= MIN_MTU || mtu >= _largeMTU ||
            (_remoteIP.length == 16 && mtu <= MIN_IPV6_MTU))
            return;
        _largeMTU = mtu;
        if (mtu < _mtu)
            _mtu = mtu;
    }
    
    /** we are resending a packet, so lets jack up the rto */
    public synchronized void messageRetransmitted(int packets) { 
        _context.statManager().addRateData("udp.congestionOccurred", _sendWindowBytes);
        _context.statManager().addRateData("udp.congestedRTO", _rto, _rttDeviation);
        _packetsRetransmitted += packets;
        congestionOccurred();
        adjustMTU();
    }

    public synchronized void packetsTransmitted(int packets) { 
        _packetsTransmitted += packets; 
    }

    /** how long does it usually take to get a message ACKed? */
    public synchronized int getRTT() { return _rtt; }
    /** how soon should we retransmit an unacked packet? */
    public synchronized int getRTO() { return _rto; }
    /** how skewed are the measured RTTs? */
    public synchronized int getRTTDeviation() { return _rttDeviation; }
    
    public synchronized int getMessagesSent() { return _messagesSent; }
    public synchronized int getMessagesReceived() { return _messagesReceived; }
    public synchronized int getPacketsTransmitted() { return _packetsTransmitted; }
    public synchronized int getPacketsRetransmitted() { return _packetsRetransmitted; }
    //public long getPacketsPeriodTransmitted() { return _packetsPeriodTransmitted; }
    //public int getPacketsPeriodRetransmitted() { return _packetsPeriodRetransmitted; }

    /** avg number of packets retransmitted for every 100 packets */
    //public long getPacketRetransmissionRate() { return _packetRetransmissionRate; }
    public synchronized int getPacketsReceived() { return _packetsReceived; }
    public synchronized int getPacketsReceivedDuplicate() { return _packetsReceivedDuplicate; }

    private static final int MTU_RCV_DISPLAY_THRESHOLD = 20;
    /** 60 */
    private static final int OVERHEAD_SIZE = PacketBuilder.IP_HEADER_SIZE + PacketBuilder.UDP_HEADER_SIZE +
                                             UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
    /** 80 */
    private static final int IPV6_OVERHEAD_SIZE = PacketBuilder.IPV6_HEADER_SIZE + PacketBuilder.UDP_HEADER_SIZE +
                                             UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;

    /** 
     *  @param size not including IP header, UDP header, MAC or IV
     */
    public synchronized void packetReceived(int size) { 
        _packetsReceived++; 
        int minMTU;
        if (_remoteIP.length == 4) {
            size += OVERHEAD_SIZE;
            minMTU = MIN_MTU;
        } else {
            size += IPV6_OVERHEAD_SIZE;
            minMTU = MIN_IPV6_MTU;
        }
        if (size <= minMTU) {
            _consecutiveSmall++;
            if (_consecutiveSmall >= MTU_RCV_DISPLAY_THRESHOLD)
                _mtuReceive = minMTU;
        } else {
            _consecutiveSmall = 0;
            if (size > _mtuReceive)
                _mtuReceive = size;
        }
    }
    
    /** 
     *  We received a backoff request, so cut our send window.
     *  NOTE: ECN sending is unimplemented, this is never called.
     */
    public void ECNReceived() {
        synchronized(this) {
            congestionOccurred();
        }
        _context.statManager().addRateData("udp.congestionOccurred", _sendWindowBytes);
        _currentSecondECNReceived = true;
        _lastReceiveTime = _context.clock().now();
    }
    
    public void dataReceived() {
        _lastReceiveTime = _context.clock().now();
    }
    
    /** when did we last send an ACK to the peer? */
    public long getLastACKSend() { return _lastACKSend; }

    /** @deprecated unused */
    public void setLastACKSend(long when) { _lastACKSend = when; }

    public long getWantedACKSendSince() { return _wantACKSendSince; }

    /**
     *  Are we out of room to send all the current unsent acks in a single packet?
     *  This is a huge threshold (134 for small MTU and 255 for large MTU)
     *  that is rarely if ever exceeded in practice.
     *  So just use a fixed threshold of half the resend acks, so that if the
     *  packet is lost the acks have a decent chance of getting retransmitted.
     *  Used only by ACKSender.
     */
    public boolean unsentACKThresholdReached() {
        //int threshold = countMaxACKData() / 4;
        //return _currentACKs.size() >= threshold;
        return _currentACKs.size() >= MAX_RESEND_ACKS / 2;
    }

    /**
     *  @return how many bytes available for acks in an ack-only packet, == MTU - 83
     *          Max of 1020
     */
    private int countMaxACKData() {
        return Math.min(PacketBuilder.ABSOLUTE_MAX_ACKS * 4,
                _mtu 
                - (_remoteIP.length == 4 ? PacketBuilder.IP_HEADER_SIZE : PacketBuilder.IPV6_HEADER_SIZE)
                - PacketBuilder.UDP_HEADER_SIZE
                - UDPPacket.IV_SIZE 
                - UDPPacket.MAC_SIZE
                - 1 // type flag
                - 4 // timestamp
                - 1 // data flag
                - 1 // # ACKs
                - 16); // padding safety
    }

    private int minRTO() {
        //if (_packetRetransmissionRate < 10)
            return MIN_RTO;
        //else if (_packetRetransmissionRate < 50)
        //    return 2*MIN_RTO;
        //else
        //    return MAX_RTO;
    }
    
    /** @return non-null */
    RemoteHostId getRemoteHostId() { return _remoteHostId; }
    
    /**
     *  TODO should this use a queue, separate from the list of msgs pending an ack?
     *  TODO bring back tail drop?
     *  TODO priority queue? (we don't implement priorities in SSU now)
     *  TODO backlog / pushback / block instead of dropping? Can't really block here.
     *  TODO SSU does not support isBacklogged() now
     */
    public void add(OutboundMessageState state) {
        if (_dead) { 
            _transport.failed(state, false);
            return;
	}
        if (state.getPeer() != this) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not for me!", new Exception("I did it"));
            _transport.failed(state, false);
            return;
	}
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Adding to " + _remotePeer + ": " + state.getMessageId());
        int rv = 0;
        // will never fail for CDPQ
        boolean fail = !_outboundQueue.offer(state);
/****
        synchronized (_outboundMessages) {
            rv = _outboundMessages.size() + 1;
            if (rv > MAX_SEND_MSGS_PENDING) { 
                // too many queued messages to one peer?  nuh uh.
                fail = true;
                rv--;
****/

         /******* proactive tail drop disabled by jr 2006-04-19 so all this is pointless

            } else if (_retransmitter != null) {
                long lifetime = _retransmitter.getLifetime();
                long totalLifetime = lifetime;
                for (int i = 1; i < msgs.size(); i++) { // skip the first, as thats the retransmitter
                    OutboundMessageState cur = msgs.get(i);
                    totalLifetime += cur.getLifetime();
                }
                long remaining = -1;
                OutNetMessage omsg = state.getMessage();
                if (omsg != null)
                    remaining = omsg.getExpiration() - _context.clock().now();
                else
                    remaining = 10*1000 - state.getLifetime();
                
                if (remaining <= 0)
                    remaining = 1; // total lifetime will exceed it anyway, guaranteeing failure
                float pDrop = totalLifetime / (float)remaining;
                pDrop = pDrop * pDrop * pDrop;
                if (false && (pDrop >= _context.random().nextFloat())) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Proactively tail dropping for " + _remotePeer.toBase64() + " (messages=" + msgs.size() 
                                  + " headLifetime=" + lifetime + " totalLifetime=" + totalLifetime + " curLifetime=" + state.getLifetime() 
                                  + " remaining=" + remaining + " pDrop=" + pDrop + ")");
                    _context.statManager().addRateData("udp.queueDropSize", msgs.size(), totalLifetime);
                    fail = true;
                } else { 
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Probabalistically allowing for " + _remotePeer.toBase64() + " (messages=" + msgs.size() 
                                   + " headLifetime=" + lifetime + " totalLifetime=" + totalLifetime + " curLifetime=" + state.getLifetime() 
                                   + " remaining=" + remaining + " pDrop=" + pDrop + ")");
                    _context.statManager().addRateData("udp.queueAllowTotalLifetime", totalLifetime, lifetime);
                    msgs.add(state);
                }

             *******/
/****
            } else {
                _outboundMessages.add(state);
            }
        }
****/
        if (fail) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping msg, OB queue full for " + toString());
            _transport.failed(state, false);
        }
    }

    /** drop all outbound messages */
    public void dropOutbound() {
        //if (_dead) return;
        _dead = true;
        //_outboundMessages = null;

            List<OutboundMessageState> tempList;
            synchronized (_outboundMessages) {
                    _retransmitter = null;
                    tempList = new ArrayList<OutboundMessageState>(_outboundMessages);
                    _outboundMessages.clear();
            }
            //_outboundQueue.drainAllTo(tempList);
            _outboundQueue.drainTo(tempList);
            for (OutboundMessageState oms : tempList) {
                _transport.failed(oms, false);
            }

        // so the ACKSender will drop this peer from its queue
        _wantACKSendSince = -1;
    }
    
    /**
     * @return number of active outbound messages remaining (unsynchronized)
     */
    public int getOutboundMessageCount() {
        if (_dead) return 0;
        return _outboundMessages.size() + _outboundQueue.size();
    }
    
    /**
     * Expire / complete any outbound messages
     * High usage -
     * OutboundMessageFragments.getNextVolley() calls this 1st.
     * TODO combine finishMessages(), allocateSend(), and getNextDelay() so we don't iterate 3 times.
     *
     * @return number of active outbound messages remaining
     */
    public int finishMessages() {
        // short circuit, unsynchronized
        if (_outboundMessages.isEmpty())
            return _outboundQueue.size();

        if (_dead) {
            dropOutbound();
            return 0;
	}

        int rv = 0;
        List<OutboundMessageState> succeeded = null;
        List<OutboundMessageState> failed = null;
        synchronized (_outboundMessages) {
            for (Iterator<OutboundMessageState> iter = _outboundMessages.iterator(); iter.hasNext(); ) {
                OutboundMessageState state = iter.next();
                if (state.isComplete()) {
                    iter.remove();
                    if (_retransmitter == state)
                        _retransmitter = null;
                    if (succeeded == null) succeeded = new ArrayList<OutboundMessageState>(4);
                    succeeded.add(state);
                } else if (state.isExpired()) {
                    iter.remove();
                    if (_retransmitter == state)
                        _retransmitter = null;
                    _context.statManager().addRateData("udp.sendFailed", state.getPushCount());
                    if (failed == null) failed = new ArrayList<OutboundMessageState>(4);
                    failed.add(state);
                } else if (state.getPushCount() > OutboundMessageFragments.MAX_VOLLEYS) {
                    iter.remove();
                    if (state == _retransmitter)
                        _retransmitter = null;
                    _context.statManager().addRateData("udp.sendAggressiveFailed", state.getPushCount());
                    if (failed == null) failed = new ArrayList<OutboundMessageState>(4);
                    failed.add(state);
                } // end (pushCount > maxVolleys)
            } // end iterating over outbound messages
            rv = _outboundMessages.size();
        }
        
        for (int i = 0; succeeded != null && i < succeeded.size(); i++) {
            OutboundMessageState state = succeeded.get(i);
            _transport.succeeded(state);
            OutNetMessage msg = state.getMessage();
            if (msg != null)
                msg.timestamp("sending complete");
        }
        
        for (int i = 0; failed != null && i < failed.size(); i++) {
            OutboundMessageState state = failed.get(i);
            OutNetMessage msg = state.getMessage();
            if (msg != null) {
                msg.timestamp("expired in the active pool");
                _transport.failed(state);
            } else {
                // it can not have an OutNetMessage if the source is the
                // final after establishment message
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to send a direct message: " + state);
            }
        }
        
        return rv + _outboundQueue.size();
    }
    
    /**
     * Pick one or more messages we want to send and allocate them out of our window
     * High usage -
     * OutboundMessageFragments.getNextVolley() calls this 2nd, if finishMessages() returned > 0.
     * TODO combine finishMessages(), allocateSend(), and getNextDelay() so we don't iterate 3 times.
     *
     * @return allocated messages to send (never empty), or null if no messages or no resources
     */
    public List<OutboundMessageState> allocateSend() {
        if (_dead) return null;
        List<OutboundMessageState> rv = null;
        synchronized (_outboundMessages) {
            for (OutboundMessageState state : _outboundMessages) {
                // We have 3 return values, because if allocateSendingBytes() returns false,
                // then we can stop iterating.
                ShouldSend should = locked_shouldSend(state);
                if (should == ShouldSend.YES) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Allocate sending (OLD) to " + _remotePeer + ": " + state.getMessageId());
                    /*
                    while (iter.hasNext()) {
                        OutboundMessageState later = (OutboundMessageState)iter.next();
                        OutNetMessage msg = later.getMessage();
                        if (msg != null)
                            msg.timestamp("not reached for allocation " + msgs.size() + " other peers");
                    }
                     */
                    if (rv == null)
                        rv = new ArrayList<OutboundMessageState>(MAX_ALLOCATE_SEND);
                    rv.add(state);
                    if (rv.size() >= MAX_ALLOCATE_SEND)
                        return rv;
                } else if (should == ShouldSend.NO_BW) {
                    // no more bandwidth available
                    // we don't bother looking for a smaller msg that would fit.
                    // By not looking further, we keep strict sending order, and that allows
                    // some efficiency in acked() below.
                    if (rv == null && _log.shouldLog(Log.DEBUG))
                        _log.debug("Nothing to send (BW) to " + _remotePeer + ", with " + _outboundMessages.size() +
                                   " / " + _outboundQueue.size() + " remaining");
                    return rv;
                } /* else {
                    OutNetMessage msg = state.getMessage();
                    if (msg != null)
                        msg.timestamp("passed over for allocation with " + msgs.size() + " peers");
                } */
            }

            // Peek at head of _outboundQueue and see if we can send it.
            // If so, pull it off, put it in _outbundMessages, test
            // again for bandwidth if necessary, and return it.
            OutboundMessageState state;
            while ((state = _outboundQueue.peek()) != null &&
                   ShouldSend.YES == locked_shouldSend(state)) {
                // we could get a different state, or null, when we poll,
                // due to AQM drops, so we test again if necessary
                OutboundMessageState dequeuedState = _outboundQueue.poll();
                if (dequeuedState != null) {
                    _outboundMessages.add(dequeuedState);
                    if (dequeuedState == state || ShouldSend.YES == locked_shouldSend(state)) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Allocate sending (NEW) to " + _remotePeer + ": " + dequeuedState.getMessageId());
                        if (rv == null)
                            rv = new ArrayList<OutboundMessageState>(MAX_ALLOCATE_SEND);
                        rv.add(state);
                        if (rv.size() >= MAX_ALLOCATE_SEND)
                            return rv;
                    }
                }
            }
        }
        if ( rv == null && _log.shouldLog(Log.DEBUG))
            _log.debug("Nothing to send to " + _remotePeer + ", with " + _outboundMessages.size() +
                       " / " + _outboundQueue.size() + " remaining");
        return rv;
    }
    
    /**
     * High usage -
     * OutboundMessageFragments.getNextVolley() calls this 3rd, if allocateSend() returned null.
     * TODO combine finishMessages(), allocateSend(), and getNextDelay() so we don't iterate 3 times.
     *
     * @return how long to wait before sending, or Integer.MAX_VALUE if we have nothing to send.
     *         If ready now, will return 0 or a negative value.
     */
    public int getNextDelay() {
        int rv = Integer.MAX_VALUE;
        if (_dead) return rv;
        long now = _context.clock().now();
        synchronized (_outboundMessages) {
            if (_retransmitter != null) {
                rv = (int)(_retransmitter.getNextSendTime() - now);
                return rv;
            }
            for (OutboundMessageState state : _outboundMessages) {
                int delay = (int)(state.getNextSendTime() - now);
                // short circuit once we hit something ready to go
                if (delay <= 0)
                    return delay;
                if (delay < rv)
                    rv = delay;
            }
        }
        // failsafe... is this OK?
        if (rv > 100 && !_outboundQueue.isEmpty())
            rv = 100;
        return rv;
    }

    /**
     *  @since 0.9.3
     */
    public boolean isBacklogged() {
        return _dead || _outboundQueue.isBacklogged();
    }

    /**
     * If set to true, we should throttle retransmissions of all but the first message in
     * flight to a peer.  If set to false, we will only throttle the initial flight of a
     * message to a peer while a retransmission is going on.
     */
    private static final boolean THROTTLE_RESENDS = true;
    /** 
     * if true, throttle the initial volley of a message if there is a resend in progress.
     * if false, always send the first volley, regardless of retransmissions (but keeping in
     * mind bw/cwin throttle, etc)
     *
     */
    private static final boolean THROTTLE_INITIAL_SEND = true;
    
    /**
     *  Always leave room for this many explicit acks.
     *  Only for data packets. Does not affect ack-only packets.
     *  This directly affects data packet overhead, adjust with care.
     */
    private static final int MIN_EXPLICIT_ACKS = 3;
    /** this is room for three explicit acks or two partial acks or one of each = 13 */
    private static final int MIN_ACK_SIZE = 1 + (4 * MIN_EXPLICIT_ACKS);

    /**
     *  how much payload data can we shove in there?
     *  @return MTU - 87, i.e. 533 or 1397 (IPv4), MTU - 107 (IPv6)
     */
    public int fragmentSize() {
        // 46 + 20 + 8 + 13 = 74 + 13 = 87 (IPv4)
        // 46 + 40 + 8 + 13 = 94 + 13 = 107 (IPv6)
        return _mtu -
               (_remoteIP.length == 4 ? PacketBuilder.MIN_DATA_PACKET_OVERHEAD : PacketBuilder.MIN_IPV6_DATA_PACKET_OVERHEAD) -
               MIN_ACK_SIZE;
    }
    
    private enum ShouldSend { YES, NO, NO_BW };

    /**
     *  Have 3 return values, because if allocateSendingBytes() returns false,
     *  then allocateSend() can stop iterating
     *
     *  Caller should synch
     */
    private ShouldSend locked_shouldSend(OutboundMessageState state) {
        long now = _context.clock().now();
        if (state.getNextSendTime() <= now) {
            OutboundMessageState retrans = _retransmitter;
            if ( (retrans != null) && ( (retrans.isExpired() || retrans.isComplete()) ) ) {
                _retransmitter = null;
                retrans = null;
	    }
            
            if ( (retrans != null) && (retrans != state) ) {
                // choke it, since there's already another message retransmitting to this
                // peer.
                _context.statManager().addRateData("udp.blockedRetransmissions", _packetsRetransmitted);
                int max = state.getMaxSends();
                if ( (max <= 0) && (!THROTTLE_INITIAL_SEND) ) {
                    //if (state.getMessage() != null)
                    //    state.getMessage().timestamp("another message is retransmitting, but we want to send our first volley...");
                } else if ( (max <= 0) || (THROTTLE_RESENDS) ) {
                    //if (state.getMessage() != null)
                    //    state.getMessage().timestamp("choked, with another message retransmitting");
                    return ShouldSend.NO;
                } else {
                    //if (state.getMessage() != null)
                    //    state.getMessage().timestamp("another message is retransmitting, but since we've already begun sending...");                    
                }
            }

            int size = state.getUnackedSize();
            if (allocateSendingBytes(size, state.getPushCount())) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Allocation of " + size + " allowed with " 
                              + getSendWindowBytesRemaining() 
                              + "/" + getSendWindowBytes() 
                              + " remaining"
                              + " for message " + state.getMessageId() + ": " + state);

                if (state.getPushCount() > 0)
                    _retransmitter = state;

                state.push();
            
                int rto = getRTO();
                state.setNextSendTime(now + rto);

                //if (peer.getSendWindowBytesRemaining() > 0)
                //    _throttle.unchoke(peer.getRemotePeer());
                return ShouldSend.YES;
            } else {
                _context.statManager().addRateData("udp.sendRejected", state.getPushCount());
                //if (state.getMessage() != null)
                //    state.getMessage().timestamp("send rejected, available=" + getSendWindowBytesRemaining());
                if (_log.shouldLog(Log.INFO))
                    _log.info("Allocation of " + size + " rejected w/ wsize=" + getSendWindowBytes()
                              + " available=" + getSendWindowBytesRemaining()
                              + " for message " + state.getMessageId() + ": " + state);
                state.setNextSendTime(now + (ACKSender.ACK_FREQUENCY / 2) +
                                      _context.random().nextInt(ACKSender.ACK_FREQUENCY)); //(now + 1024) & ~SECOND_MASK);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Retransmit after choke for next send time in " + (state.getNextSendTime()-now) + "ms");
                //_throttle.choke(peer.getRemotePeer());

                //if (state.getMessage() != null)
                //    state.getMessage().timestamp("choked, not enough available, wsize=" 
                //                                 + getSendWindowBytes() + " available="
                //                                 + getSendWindowBytesRemaining());
                return ShouldSend.NO_BW;
            }
        } // nextTime <= now 

        return ShouldSend.NO;
    }
    
    /**
     *  A full ACK was received.
     *  TODO if messages awaiting ack were a HashMap<Long, OutboundMessageState> this would be faster.
     *
     *  @return true if the message was acked for the first time
     */
    public boolean acked(long messageId) {
        if (_dead) return false;
        OutboundMessageState state = null;
        synchronized (_outboundMessages) {
            for (Iterator<OutboundMessageState> iter = _outboundMessages.iterator(); iter.hasNext(); ) {
                state = iter.next();
                if (state.getMessageId() == messageId) {
                    iter.remove();
                    break;
                } else if (state.getPushCount() <= 0) {
                    // _outboundMessages is ordered, so once we get to a msg that
                    // hasn't been transmitted yet, we can stop
                    state = null;
                    break;
                } else {
                    state = null;
                }
            }
            if ( (state != null) && (state == _retransmitter) )
                _retransmitter = null;
        }
        
        if (state != null) {
            int numSends = state.getMaxSends();
            //if (state.getMessage() != null) {
            //    state.getMessage().timestamp("acked after " + numSends
            //                                 + " lastReceived: " 
            //                                 + (_context.clock().now() - getLastReceiveTime())
            //                                 + " lastSentFully: " 
            //                                 + (_context.clock().now() - getLastSendFullyTime()));
            //}

            if (_log.shouldLog(Log.INFO))
                _log.info("Received ack of " + messageId + " by " + _remotePeer
                          + " after " + state.getLifetime() + " and " + numSends + " sends");
            _context.statManager().addRateData("udp.sendConfirmTime", state.getLifetime());
            if (state.getFragmentCount() > 1)
                _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount());
            _context.statManager().addRateData("udp.sendConfirmVolley", numSends);
            _transport.succeeded(state);
            int numFragments = state.getFragmentCount();
            // this adjusts the rtt/rto/window/etc
            messageACKed(state.getMessageSize(), state.getLifetime(), numSends);
            //if (getSendWindowBytesRemaining() > 0)
            //    _throttle.unchoke(peer.getRemotePeer());
            
        } else {
            // dupack, likely
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Received an ACK for a message not pending: " + messageId);
        }
        return state != null;
    }
    
    /**
     *  A partial ACK was received. This is much less common than full ACKs.
     *
     *  @return true if the message was completely acked for the first time
     */
    public boolean acked(ACKBitfield bitfield) {
        if (_dead)
            return false;
        
        if (bitfield.receivedComplete()) {
            return acked(bitfield.getMessageId());
        }
    
        OutboundMessageState state = null;
        boolean isComplete = false;
        synchronized (_outboundMessages) {
            for (Iterator<OutboundMessageState> iter = _outboundMessages.iterator(); iter.hasNext(); ) {
                state = iter.next();
                if (state.getMessageId() == bitfield.getMessageId()) {
                    boolean complete = state.acked(bitfield);
                    if (complete) {
                        isComplete = true;
                        iter.remove();
                        if (state == _retransmitter)
                            _retransmitter = null;
                    }
                    break;
                } else if (state.getPushCount() <= 0) {
                    // _outboundMessages is ordered, so once we get to a msg that
                    // hasn't been transmitted yet, we can stop
                    state = null;
                    break;
                } else {
                    state = null;
                }
            }
        }
        
        if (state != null) {
            int numSends = state.getMaxSends();
                        
            int numACKed = bitfield.ackCount();
            _context.statManager().addRateData("udp.partialACKReceived", numACKed);
            
            if (_log.shouldLog(Log.INFO))
                _log.info("Received partial ack of " + state.getMessageId() + " by " + _remotePeer
                          + " after " + state.getLifetime() + " and " + numSends + " sends: " + bitfield + ": completely removed? " 
                          + isComplete + ": " + state);
            
            if (isComplete) {
                _context.statManager().addRateData("udp.sendConfirmTime", state.getLifetime());
                if (state.getFragmentCount() > 1)
                    _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount());
                _context.statManager().addRateData("udp.sendConfirmVolley", numSends);
                //if (state.getMessage() != null)
                //    state.getMessage().timestamp("partial ack to complete after " + numSends);
                _transport.succeeded(state);
                
                // this adjusts the rtt/rto/window/etc
                messageACKed(state.getMessageSize(), state.getLifetime(), numSends);
                //if (state.getPeer().getSendWindowBytesRemaining() > 0)
                //    _throttle.unchoke(state.getPeer().getRemotePeer());

            } else {
                //if (state.getMessage() != null)
                //    state.getMessage().timestamp("partial ack after " + numSends + ": " + bitfield.toString());
            }
            return isComplete;
        } else {
            // dupack
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received an ACK for a message not pending: " + bitfield);
            return false;
        }
    }
    
    /**
     * Transfer the basic activity/state from the old peer to the current peer
     *
     * @param oldPeer non-null
     */
    public void loadFrom(PeerState oldPeer) {
        _rto = oldPeer._rto;
        _rtt = oldPeer._rtt;
        _rttDeviation = oldPeer._rttDeviation;
        _slowStartThreshold = oldPeer._slowStartThreshold;
        _sendWindowBytes = oldPeer._sendWindowBytes;
        oldPeer._dead = true;
        
        List<Long> tmp = new ArrayList<Long>();
        // AIOOBE from concurrent access
        //tmp.addAll(oldPeer._currentACKs);
        for (Long l : oldPeer._currentACKs) {
            tmp.add(l);
        }
        oldPeer._currentACKs.clear();

        if (!_dead) {
            _currentACKs.addAll(tmp);
	}
        
        List<ResendACK> tmp3 = new ArrayList<ResendACK>();
        tmp3.addAll(oldPeer._currentACKsResend);
        oldPeer._currentACKsResend.clear();

        if (!_dead) {
            _currentACKsResend.addAll(tmp3);
	}
        
        Map<Long, InboundMessageState> msgs = new HashMap<Long, InboundMessageState>();
        synchronized (oldPeer._inboundMessages) {
            msgs.putAll(oldPeer._inboundMessages);
            oldPeer._inboundMessages.clear();
        }
        if (!_dead) {
            synchronized (_inboundMessages) { _inboundMessages.putAll(msgs); }
	}
        msgs.clear();
        
        List<OutboundMessageState> tmp2 = new ArrayList<OutboundMessageState>();
        OutboundMessageState retransmitter = null;
        synchronized (oldPeer._outboundMessages) {
            tmp2.addAll(oldPeer._outboundMessages);
            oldPeer._outboundMessages.clear();
            retransmitter = oldPeer._retransmitter;
            oldPeer._retransmitter = null;
        }
        if (!_dead) {
            synchronized (_outboundMessages) {
                _outboundMessages.addAll(tmp2);
                _retransmitter = retransmitter;
            }
        }
    }

    /**
     *  Convenience for OutboundMessageState so it can fail itself
     *  @since 0.9.3
     */
    public UDPTransport getTransport() {
        return _transport;
    }

    /**
     *  A message ID and a timestamp. Used for the resend ACKS.
     *  @since 0.9.17
     */
    private static class ResendACK {
        public final Long id;
        public final long time;

        public ResendACK(Long id, long time) {
            this.id = id;
            this.time = time;
        }
    }

    // why removed? Some risk of dups in OutboundMessageFragments._activePeers ???

    /*
    public int hashCode() {
        if (_remotePeer != null) 
            return _remotePeer.hashCode();
        else 
            return super.hashCode();
    }
    public boolean equals(Object o) {
        if (o == this) return true;
        if (o == null) return false;
        if (o instanceof PeerState) {
            PeerState s = (PeerState)o;
            if (_remotePeer == null)
                return o == this;
            else
                return _remotePeer.equals(s.getRemotePeer());
        } else {
            return false;
        }
    }
    */
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append(_remoteHostId.toString());
        if (_remotePeer != null)
            buf.append(" ").append(_remotePeer.toBase64().substring(0,6));

        buf.append(_isInbound? " IB " : " OB ");
        long now = _context.clock().now();
        buf.append(" recvAge: ").append(now-_lastReceiveTime);
        buf.append(" sendAge: ").append(now-_lastSendFullyTime);
        buf.append(" sendAttemptAge: ").append(now-_lastSendTime);
        buf.append(" sendACKAge: ").append(now-_lastACKSend);
        buf.append(" lifetime: ").append(now-_keyEstablishedTime);
        buf.append(" cwin: ").append(_sendWindowBytes);
        buf.append(" acwin: ").append(_sendWindowBytesRemaining);
        buf.append(" consecFail: ").append(_consecutiveFailedSends);
        buf.append(" recv OK/Dup: ").append(_packetsReceived).append('/').append(_packetsReceivedDuplicate);
        buf.append(" send OK/Dup: ").append(_packetsTransmitted).append('/').append(_packetsRetransmitted);
        buf.append(" IBM: ").append(_inboundMessages.size());
        buf.append(" OBQ: ").append(_outboundQueue.size());
        buf.append(" OBL: ").append(_outboundMessages.size());
        return buf.toString();
    }
}
