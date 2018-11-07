/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2018 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/

package tuwien.auto.calimero.server.knxnetip;

import static tuwien.auto.calimero.DataUnitBuilder.toHex;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.KeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.rfc7748.X25519;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.knxnetip.KNXnetIPDevMgmt;
import tuwien.auto.calimero.knxnetip.KnxSecureException;
import tuwien.auto.calimero.knxnetip.SecureConnection;
import tuwien.auto.calimero.knxnetip.servicetype.KNXnetIPHeader;

/** Secure sessions container for KNX IP secure unicast connections. */
class SecureSession {

	static {
		if (Runtime.version().version().get(0) < 11)
			Security.addProvider(new BouncyCastleProvider());
	}

	private static final int SecureSvc = 0x0950;
	private static final int SessionReq = 0x0951; // 1. client -> server
	private static final int SessionRes = 0x0952; // 2. server -> client
	private static final int SessionAuth = 0x0953; // 3. client -> server
	private static final int SessionStatus = 0x0954; // 4. server -> client


	private static final int macSize = 16; // [bytes]
	private static final int keyLength = 32; // [bytes]

	private final DatagramSocket socket;
	private final Logger logger;

	static final int pidDeviceAuth = 92; // PDT generic 16
	static final int pidUserPwdHashes = 93; // PDT generic 16
	static final int pidSecuredServices = 94;
	static final int pidLatencyTolerance = 95;
	static final int pidSyncLatencyTolerance = 96;
	private final byte[] sno;
	private final Key deviceAuthKey;

	private static AtomicLong sessionCounter = new AtomicLong();

	static class Session {
		private final InetSocketAddress client;
		final Key secretKey;
		final AtomicLong sendSeq = new AtomicLong();
		long lastUpdate = System.nanoTime() / 1_000_000;
		private int userId;

		Session(final int sessionId, final InetSocketAddress client, final Key secretKey) {
			this.client = client;
			this.secretKey = secretKey;
		}
	}
	final Map<Integer, Session> sessions = new ConcurrentHashMap<>();


	SecureSession(final ControlEndpointService ctrlEndpoint) {
		socket = ctrlEndpoint.getSocket();
		final String lock = new String(Character.toChars(0x1F512));
		final String name = ctrlEndpoint.getServiceContainer().getName();
		logger = LoggerFactory.getLogger("calimero.server.knxnetip." + name + ".KNX IP " + lock + " Session");
		sno = deriveSerialNumber(ctrlEndpoint.getSocket().getLocalAddress());
		deviceAuthKey = createSecretKey(new byte[16]);
	}

	boolean acceptService(final KNXnetIPHeader h, final byte[] data, final int offset, final InetAddress src,
		final int port, final Object svcHandler) throws KNXFormatException, IOException {

		int sessionId = 0;
		try {
			if (h.getServiceType() == SessionReq) {
				final ByteBuffer res = establishSession(new InetSocketAddress(src, port), h, data, offset);
				socket.send(new DatagramPacket(res.array(), res.position(), src, port));
				logger.trace("currently open sessions: {}", sessions.keySet());
				return true;
			}
			if (h.getServiceType() == SecureSvc) {
				sessionId = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
				final Session session = sessions.get(sessionId);
				if (session == null) {
					logger.warn("invalid secure session ID {}", sessionId);
					return false;
				}
				final Key secretKey = session.secretKey;
				final Object[] fields = SecureConnection.unwrap(h, data, offset, secretKey);
				final int sid = (int) fields[0];
				final long seq = (long) fields[1];
				final long sno = (long) fields[2];
				final int tag = (int) fields[3];
				final byte[] knxipPacket = (byte[]) fields[4];

				final KNXnetIPHeader svcHeader = new KNXnetIPHeader(knxipPacket, 0);
				logger.debug("received session {} seq {} (S/N {} tag {}) {}: {}", sid, seq, sno, tag, svcHeader,
						toHex(knxipPacket, " "));
				final long lastValidPacket = System.nanoTime() / 1000_000L;
				session.lastUpdate = lastValidPacket;

				if (svcHeader.getServiceType() == SessionAuth) {
					int status = AuthSuccess;
					try {
						sessionAuth(session, knxipPacket, 6);
						logger.debug("client {} authorized for session {} with user ID {}", session.client, sessionId, session.userId);
					}
					catch (final KnxSecureException e) {
						logger.info("secure session {}: {}", sessionId, e.getMessage());
						status = AuthFailed;
					}
					sendStatusInfo(sessionId, session.sendSeq.getAndIncrement(), status, src, port);
					if (status == AuthFailed)
						sessions.remove(sessionId);
				}
				else if (svcHeader.getServiceType() == SessionStatus) {
					final int status = sessionStatus(svcHeader, data, svcHeader.getStructLength());
					logger.info("secure session {}: {}", sid, statusMsg(status));
				}
				else {
					// forward to service handler
					final int start = svcHeader.getStructLength();
					if (svcHandler instanceof ControlEndpointService) {
						if (svcHeader.getServiceType() == KNXnetIPHeader.CONNECT_REQ) {
							connections.put(new InetSocketAddress(src, port), sessionId);
						}
						final ControlEndpointService ces = (ControlEndpointService) svcHandler;
						return ces.acceptControlService(sessionId, svcHeader, knxipPacket, start, src, port);
					}
					else
						return ((DataEndpointServiceHandler) svcHandler).acceptDataService(svcHeader, knxipPacket, start);
				}
				return true;
			}
		}
		catch (final KnxSecureException e) {
			logger.error("error processing {}, {}", h, e.getMessage());
			sendStatusInfo(sessionId, 0, Unauthorized, src, port);
		}
		return false;
	}

	// temporary
	private final Map<InetSocketAddress, Integer> connections = new ConcurrentHashMap<>();

	int registerConnection(final int connType, final InetSocketAddress ctrlEndpt, final int channelId) {
		final int sid = connections.getOrDefault(ctrlEndpt, 0);
		// only session with user id 1 has proper access level for management access
		if (connType == KNXnetIPDevMgmt.DEVICE_MGMT_CONNECTION && sid > 0 && sessions.get(sid).userId > 1)
			return 0;
		return sid;
	}

	private ByteBuffer establishSession(final InetSocketAddress remote, final KNXnetIPHeader h, final byte[] data, final int offset) {

		final byte[] clientKey = Arrays.copyOfRange(data, offset + 8, h.getTotalLength());
		final byte[] publicKey;
		final byte[] sharedSecret;

		if (Runtime.version().version().get(0) < 11) {
			final byte[] privateKey = new byte[keyLength];
			publicKey = new byte[keyLength];
			generateKeyPair(privateKey, publicKey);
			sharedSecret = keyAgreement(privateKey, clientKey);
		}
		else {
			try {
				final KeyPair keyPair = generateKeyPair();
				// we're compiling for java 9
//				final BigInteger u = ((XECPublicKey) public1).getU();
				final MethodHandle bind = MethodHandles.lookup().bind(keyPair.getPublic(), "getU", MethodType.methodType(BigInteger.class));
				final BigInteger u = (BigInteger) bind.invoke();
				publicKey = u.toByteArray();
				reverse(publicKey);

				sharedSecret = keyAgreement(keyPair.getPrivate(), clientKey);
			}
			catch (final Throwable e) {
				throw new KnxSecureException("error creating secure session keys for " + remote, e);
			}
		}

		final Key secretKey = createSecretKey(sessionKey(sharedSecret));

		final int sessionId = newSessionId();
		if (sessionId != 0)
			sessions.put(sessionId, new Session(sessionId, remote, secretKey));
		logger.debug("establish secure session {} for {}", sessionId, remote);

		return sessionResponse(sessionId, publicKey, clientKey);
	}

	private ByteBuffer sessionResponse(final int sessionId, final byte[] publicKey, final byte[] clientPublicKey) {
		final int len = sessionId == 0 ? 8 : 0x38;
		final ByteBuffer buf = ByteBuffer.allocate(len);
		buf.put(new KNXnetIPHeader(SessionRes, len - 6).toByteArray());
		buf.putShort((short) sessionId);
		if (sessionId == 0)
			return buf;

		buf.put(publicKey);
		final byte[] xor = xor(publicKey, 0, clientPublicKey, 0, keyLength);
		final byte[] mac = cbcMacSimple(xor, 0, xor.length);
		encrypt(mac, sessions.get(sessionId).secretKey);
		buf.put(mac);
		return buf;
	}

	private void sessionAuth(final Session session, final byte[] data, final int offset) {
		final ByteBuffer buffer = ByteBuffer.wrap(data, offset, data.length - offset);
		final int userId = buffer.getShort() & 0xffff;
		final byte[] mac = new byte[macSize];
		buffer.get(mac);

		// TODO keys
		final byte[] serverPublicKey = new byte[keyLength];
		final byte[] clientPublicKey = new byte[keyLength];
		final byte[] xor = xor(serverPublicKey, 0, clientPublicKey, 0, keyLength);
		final byte[] verifyAgainst = cbcMacSimple(xor, 0, keyLength);
		final boolean authenticated = Arrays.equals(mac, verifyAgainst);
		if (!authenticated) {
			logger.warn("not yet implemented: we don't verify session.auth (user ID {})", userId);
//			final String packet = toHex(Arrays.copyOfRange(data, offset - 6, offset - 6 + 0x38), " ");
//			throw new KnxSecureException("authentication failed for session auth " + packet);
		}

		if (userId < 1 || userId > 0x7F)
			throw new KnxSecureException("user ID " + userId + " out of range [1..127]");
		session.userId = userId;
	}

	private void sendStatusInfo(final int sessionId, final long seq, final int status, final InetAddress remote, final int port) {
		try {
			final byte[] packet = statusInfo(sessionId, seq, status);
			socket.send(new DatagramPacket(packet, packet.length, remote, port));
		}
		catch (IOException | RuntimeException e) {
			logger.error("sending session {} status {} to {}:{}", sessionId, statusMsg(status), remote, port, e);
		}
	}

	private byte[] statusInfo(final int sessionId, final long seq, final int status) {
		final ByteBuffer packet = ByteBuffer.allocate(6 + 2);
		packet.put(new KNXnetIPHeader(SessionStatus, 2).toByteArray());
		packet.put((byte) status);
		final int msgTag = 0; // NYI
		return newSecurePacket(sessionId, seq, msgTag, packet.array());
	}

	private int sessionStatus(final KNXnetIPHeader h, final byte[] data, final int offset) throws KNXFormatException {
		if (h.getServiceType() != SessionStatus)
			throw new KNXIllegalArgumentException("no secure session status");
		if (h.getTotalLength() != 8)
			throw new KNXFormatException("invalid length " + h.getTotalLength() + " for secure session status");

		final int status = data[offset] & 0xff;
		return status;
	}

	// session status is one of:
	private static final int AuthSuccess = 0;
	private static final int AuthFailed = 1;
	private static final int Unauthorized = 2;
	private static final int Timeout = 3;

	private static String statusMsg(final int status) {
		final String[] msg = { "authorization success", "authorization failed", "unauthorized", "timeout" };
		if (status > 3)
			return "unknown status " + status;
		return msg[status];
	}

	private static final Duration sessionTimeout = Duration.ofMinutes(2);

	void closeDormantSessions() {
		sessions.forEach(this::checkSessionTimeout);
	}

	// if we don't receive a valid secure packet for 2 minutes, we close the session (and any open connections)
	private void checkSessionTimeout(final int sessionId, final Session session) {
		final long now = System.nanoTime() / 1_000_000;
		final Duration dormant = Duration.ofMillis(now - session.lastUpdate);
		if (dormant.compareTo(sessionTimeout) > 0) {
			logger.info("secure session {} timed out after {} seconds - close session", sessionId, dormant.toSeconds());
			sessionTimeout(sessionId, session);
		}
	}

	private void sessionTimeout(final int sessionId, final Session session) {
		final long seq = session.sendSeq.getAndIncrement();
		sendStatusInfo(sessionId, (int) seq, Timeout, session.client.getAddress(), session.client.getPort());
		sessions.remove(sessionId);
	}

	byte[] newSecurePacket(final int sessionId, final long seq, final int msgTag, final byte[] knxipPacket) {
		final Key secretKey = sessions.get(sessionId).secretKey;
		return SecureConnection.newSecurePacket(sessionId, seq, sno, msgTag, knxipPacket, secretKey);
	}

	byte[] newSecurePacket(final int sessionId, final byte[] knxipPacket) {
		final long seq = sessions.get(sessionId).sendSeq.getAndIncrement();
		final int msgTag = 0;
		return newSecurePacket(sessionId, seq, msgTag, knxipPacket);
	}

	private void encrypt(final byte[] mac, final Key secretKey) {
		SecureConnection.encrypt(mac, 0, secretKey, securityInfo(new byte[16], 0, 0xff00));
	}

	private byte[] cbcMacSimple(final byte[] data, final int offset, final int length) {
		final byte[] log = Arrays.copyOfRange(data, offset, offset + length);
		logger.trace("authenticating (length {}): {}", length, toHex(log, " "));

		try {
			final Cipher cipher = Cipher.getInstance("AES/CBC/ZeroBytePadding");
			final IvParameterSpec ivSpec = new IvParameterSpec(new byte[16]);
			cipher.init(Cipher.ENCRYPT_MODE, deviceAuthKey, ivSpec);

			final byte[] result = cipher.doFinal(data, offset, length);
			final byte[] mac = Arrays.copyOfRange(result, result.length - macSize, result.length);
			return mac;
		}
		catch (final GeneralSecurityException e) {
			throw new KnxSecureException("calculating CBC-MAC of " + toHex(log, " "), e);
		}
	}

	private static void generateKeyPair(final byte[] privateKey, final byte[] publicKey) {
		new SecureRandom().nextBytes(privateKey);
		X25519.scalarMultBase(privateKey, 0, publicKey, 0);
	}

	// use java SunEC provider
	private static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
		final KeyPairGenerator gen = KeyPairGenerator.getInstance("X25519");
		return gen.generateKeyPair();
	}

	private static byte[] keyAgreement(final byte[] privateKey, final byte[] spk) {
		final byte[] sharedSecret = new byte[keyLength];
		X25519.scalarMult(privateKey, 0, spk, 0, sharedSecret, 0);
		return sharedSecret;
	}

	// use java SunEC provider
	// we're compiling for java 9, so reflectively access X25519 stuff
	private static byte[] keyAgreement(final PrivateKey privateKey, final byte[] spk) throws GeneralSecurityException {
		final byte[] reversed = spk.clone();
		reverse(reversed);
//		final KeySpec spec = new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, reversed));
		final KeySpec spec;
		try {
			final AlgorithmParameterSpec params = (AlgorithmParameterSpec) constructor("java.security.spec.NamedParameterSpec",
					String.class).newInstance("X25519");
			spec = (KeySpec) constructor("java.security.spec.XECPublicKeySpec", AlgorithmParameterSpec.class, BigInteger.class)
					.newInstance(params, new BigInteger(1, reversed));
		}
		catch (IllegalArgumentException | ReflectiveOperationException e) {
			throw new KnxSecureException("creating key spec for client public key", e);
		}

		final PublicKey pubKey = KeyFactory.getInstance("X25519").generatePublic(spec);
		final KeyAgreement ka = KeyAgreement.getInstance("X25519");
		ka.init(privateKey);
		ka.doPhase(pubKey, true);
		return ka.generateSecret();
	}

	private static byte[] sessionKey(final byte[] sharedSecret) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			final byte[] hash = digest.digest(sharedSecret);
			return Arrays.copyOfRange(hash, 0, 16);
		}
		catch (final NoSuchAlgorithmException e) {
			// every platform is required to support SHA-256
			throw new KnxSecureException("platform does not support SHA-256 algorithm", e);
		}
	}

	private static Key createSecretKey(final byte[] key) {
		if (key.length != 16)
			throw new KNXIllegalArgumentException("KNX secret key has to be 16 bytes in length");
		return new SecretKeySpec(key, 0, key.length, "AES");
	}

	private static byte[] securityInfo(final byte[] data, final int offset, final int lengthInfo) {
		final byte[] secInfo = Arrays.copyOfRange(data, offset, offset + 16);
		secInfo[14] = (byte) (lengthInfo >> 8);
		secInfo[15] = (byte) lengthInfo;
		return secInfo;
	}

	private static byte[] deriveSerialNumber(final InetAddress addr) {
		if (addr != null) {
			try {
				final NetworkInterface netif = NetworkInterface.getByInetAddress(addr);
				if (netif != null) {
					final byte[] hardwareAddress = netif.getHardwareAddress();
					if (hardwareAddress != null)
						return Arrays.copyOf(hardwareAddress, 6);
				}
			}
			catch (final SocketException e) {}
		}
		return new byte[6];
	}

	// NYI check for reuse of session ID on overflow, currently we assume ID is already free
	private static int newSessionId() {
		return (int) (sessionCounter.getAndIncrement() % 0xfffe) + 1;
	}

	private static byte[] xor(final byte[] a, final int offsetA, final byte[] b, final int offsetB, final int len) {
		if (a.length - len < offsetA || b.length - len < offsetB)
			throw new KNXIllegalArgumentException("illegal offset or length");
		final byte[] res = new byte[len];
		for (int i = 0; i < len; i++)
			res[i] = (byte) (a[i + offsetA] ^ b[i + offsetB]);
		return res;
	}

	private static void reverse(final byte[] array) {
		for (int i = 0; i < array.length / 2; i++) {
			final byte b = array[i];
			array[i] = array[array.length - 1 - i];
			array[array.length - 1 - i] = b;
		}
	}

	private static Constructor<?> constructor(final String className, final Class<?>... parameterTypes)
		throws ReflectiveOperationException {
		final Class<?> clazz = Class.forName(className);
		final Constructor<?> constructor = clazz.getConstructor(parameterTypes);
		return constructor;
	}
}