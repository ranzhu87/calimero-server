/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2016, 2018 B. Malinowsky

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

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.slf4j.Logger;

import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.internal.UdpSocketLooper;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.knxnetip.servicetype.ErrorCodes;
import tuwien.auto.calimero.knxnetip.servicetype.KNXnetIPHeader;
import tuwien.auto.calimero.knxnetip.util.HPAI;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.log.LogService.LogLevel;

abstract class ServiceLooper extends UdpSocketLooper implements Runnable
{
	final KNXnetIPServer server;
	final Logger logger;
	boolean useNat;

	ServiceLooper(final KNXnetIPServer server, final DatagramSocket socket, final int receiveBufferSize,
		final int socketTimeout)
	{
		super(socket, true, receiveBufferSize, socketTimeout, 0);
		this.server = server;
		this.logger = server.logger;
	}

	ServiceLooper(final KNXnetIPServer server, final DatagramSocket socket, final boolean closeSocket,
		final int receiveBufferSize, final int socketTimeout)
	{
		super(socket, closeSocket, receiveBufferSize, socketTimeout, 0);
		this.server = server;
		this.logger = server.logger;
	}

	@Override
	public void run()
	{
		try {
			loop();
			cleanup(LogLevel.DEBUG, null);
		}
		catch (final IOException e) {
			cleanup(LogLevel.ERROR, e);
		}
		catch (final RuntimeException e) {
			final Object id = s != null && !s.isClosed() ? s.getLocalSocketAddress() : Thread.currentThread().getName();
			logger.error("runtime exception in service loop of {}", id, e);
			cleanup(LogLevel.INFO, null);
		}
	}

	@Override
	public void onReceive(final InetSocketAddress source, final byte[] data, final int offset, final int length)
		throws IOException
	{
		try {
			final KNXnetIPHeader h = new KNXnetIPHeader(data, offset);
			if (!sanitize(h, length))
				return;
			if (!handleServiceType(h, data, offset + h.getStructLength(), source.getAddress(), source.getPort())) {
				final int svc = h.getServiceType();
				logger.info("received packet from {} with unknown service type 0x{} - ignored", source, Integer.toHexString(svc));
			}
		}
		catch (final KNXFormatException e) {
			logger.warn("received invalid frame", e);
		}
	}

	@Override
	protected void onTimeout()
	{
		logger.error("socket timeout - ignored, but should not happen");
	}

	boolean checkVersion(final KNXnetIPHeader h)
	{
		final boolean ok = h.getVersion() == KNXnetIPConnection.KNXNETIP_VERSION_10;
		if (!ok)
			logger.warn("KNXnet/IP " + (h.getVersion() >> 4) + "." + (h.getVersion() & 0xf) + " "
					+ ErrorCodes.getErrorMessage(ErrorCodes.VERSION_NOT_SUPPORTED));
		return ok;
	}

	abstract boolean handleServiceType(KNXnetIPHeader h, byte[] data, int offset, InetAddress src, int port)
		throws KNXFormatException, IOException;

	void cleanup(final LogLevel level, final Throwable t)
	{
		LogService.log(logger, level, "cleanup {}", Thread.currentThread().getName(), t);
	}

	DatagramSocket getSocket()
	{
		return s;
	}

	// logEndpointType: 0 = don't log, 1 = ctrl endpt, 2 = data endpt
	InetSocketAddress createResponseAddress(final HPAI endpoint, final InetAddress senderHost, final int senderPort,
		final int logEndpointType)
	{
		final InetAddress resIP = endpoint.getAddress();
		final int resPort = endpoint.getPort();
		// in NAT aware mode, if the data EP is incomplete or left
		// empty, we fall back to the IP address and port of the sender
		final InetSocketAddress addr;
		final String type = logEndpointType == 1 ? "control" : logEndpointType == 2 ? "data" : "";
		// if we once decided on NAT aware communication, we will stick to it,
		// regardless whether subsequent HPAIs contain useful information
		if (useNat) {
			addr = new InetSocketAddress(senderHost, senderPort);
			if (logEndpointType != 0)
				logger.debug("NAT aware: using {} endpoint {} for responses", type, addr);
		}
		else if (resIP.isAnyLocalAddress() || resPort == 0) {
			addr = new InetSocketAddress(senderHost, senderPort);
			useNat = true;
			if (logEndpointType != 0)
				logger.debug("NAT aware: using {} endpoint {} for client response", type, addr);
		}
		else {
			addr = new InetSocketAddress(resIP, resPort);
			if (logEndpointType != 0)
				logger.trace("using client-assigned {} endpoint {} for responses", type, addr);
		}
		return addr;
	}

	void fireResetRequest(final String endpointName, final InetSocketAddress ctrlEndpoint)
	{
		final ShutdownEvent se = new ShutdownEvent(server, endpointName, ctrlEndpoint);
		server.listeners().fire(l -> l.onResetRequest(se));
	}

	static RuntimeException wrappedException(final Exception e)
	{
		final RuntimeException rte = new RuntimeException(e);
		rte.setStackTrace(e.getStackTrace());
		return rte;
	}

	private boolean sanitize(final KNXnetIPHeader h, final int length)
	{
		if (h.getTotalLength() > length)
			logger.warn("received frame length does not match - ignored");
		else if (h.getServiceType() == 0)
			// check service type for 0 (invalid type), so unused service types of us can stay 0 by default
			logger.warn("received frame with service type 0 - ignored");
		else
			return true;
		return false;
	}
}
