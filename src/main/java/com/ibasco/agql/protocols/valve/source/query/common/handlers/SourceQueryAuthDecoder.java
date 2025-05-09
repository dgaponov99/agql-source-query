/*
 * Copyright (c) 2022 Asynchronous Game Query Library
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

package com.ibasco.agql.protocols.valve.source.query.common.handlers;

import com.ibasco.agql.core.AbstractRequest;
import com.ibasco.agql.core.Envelope;
import com.ibasco.agql.core.NettyChannelContext;
import com.ibasco.agql.core.util.Bytes;
import com.ibasco.agql.core.util.MessageEnvelopeBuilder;
import com.ibasco.agql.protocols.valve.source.query.SourceQuery;
import com.ibasco.agql.protocols.valve.source.query.common.exceptions.SourceChallengeException;
import com.ibasco.agql.protocols.valve.source.query.common.message.SourceQueryAuthRequest;
import com.ibasco.agql.protocols.valve.source.query.common.packets.SourceQuerySinglePacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * A special source query decoder that handles challenge-response based protocols
 *
 * @param <T>
 *         The underlying source query request type supporting challenge-response implementation
 *
 * @author Rafael Luis Ibasco
 */
abstract public class SourceQueryAuthDecoder<T extends SourceQueryAuthRequest> extends SourceQueryDecoder<T> {

    private final Class<T> requestClass;

    private final int responseHeader;

    /**
     * <p>Constructor for SourceQueryAuthDecoder.</p>
     *
     * @param requestClass
     *         a {@link java.lang.Class} object
     * @param responseHeader
     *         a int
     */
    protected SourceQueryAuthDecoder(Class<T> requestClass, int responseHeader) {
        this.requestClass = Objects.requireNonNull(requestClass, "Request class not provided");
        this.responseHeader = responseHeader;
    }

    /** {@inheritDoc} */
    @Override
    protected final boolean acceptPacket(SourceQueryMessage msg) {
        //some servers throw an empty info response for any type of requests (info, players and rules) so as long as we get a matching request AND the header and packet is empty, then we accept it
        boolean emptyInfoResponse = (msg.hasHeader(SourceQuery.SOURCE_QUERY_INFO_RES) || msg.hasHeader(SourceQuery.GOLD_SOURCE_QUERY_INFO_RES)) && msg.getPacket().content().readableBytes() == 0;
        boolean accept = msg.hasRequest(requestClass) && (emptyInfoResponse || msg.hasHeader(responseHeader) || msg.hasHeader(SourceQuery.SOURCE_QUERY_CHALLENGE_RES));
        if (!accept)
            debug("Rejected message '{}' with response header '{}' (Expected request: {}, Expected response header: {})", msg.getRequest(), msg.getPacket().getHeader(), requestClass, responseHeader);
        else
            debug("Accepted message '{}' with response header '{}' (Expected request: {}, Expected response header: {})", msg.getRequest(), msg.getPacket().getHeader(), requestClass, responseHeader);
        return accept;
    }

    /** {@inheritDoc} */
    @Override
    protected final Object decodePacket(ChannelHandlerContext ctx, T request, SourceQuerySinglePacket packet) throws Exception {
        NettyChannelContext context = NettyChannelContext.getContext(ctx.channel());
        //did we receive a challenge response from the server?
        if (packet.getHeader() == SourceQuery.SOURCE_QUERY_CHALLENGE_RES) {
            Envelope<AbstractRequest> envelope = context.properties().envelope();
            ByteBuf payload = packet.content();
            //ensure we have bytes to decode
            if (!payload.isReadable() || payload.readableBytes() < 4) {
                debug("Not enough bytes available to decode a challenge number: {}", payload.readableBytes());
                ctx.fireExceptionCaught(new SourceChallengeException("Not enough bytes available to decode challenge number", -1));
                return null;
            }
            int challenge = payload.readIntLE();
            //if auto update is not set, throw an exception instead
            if (!request.isAutoUpdate()) {
                debug("Auto-Update challenge is disabled. Exception will be thrown");
                ctx.fireExceptionCaught(new SourceChallengeException(String.format("Server '%s' responded with a challenge number: '%d' (%s). Please re-send the request using the received challenge number.", envelope.recipient(), challenge, Bytes.toHexString(challenge, ByteOrder.LITTLE_ENDIAN)), challenge));
                return null;
            }
            if (isDebugEnabled()) {
                debug("Got challenge response: {} ({})", challenge, Bytes.toHexString(challenge, ByteOrder.LITTLE_ENDIAN));
                debug("Resending '{}' request with challenge (Challenge: {} ({}), Destination: {})", request.getClass().getSimpleName(), challenge, Bytes.toHexString(challenge, ByteOrder.LITTLE_ENDIAN), context.properties().envelope().recipient());
            }
            request.setChallenge(challenge);
            //resend auth request
            Envelope<AbstractRequest> reauthRequest = MessageEnvelopeBuilder.createFrom(envelope, request).build();
            ChannelFuture writeFuture = ctx.channel().writeAndFlush(reauthRequest);
            if (writeFuture.isDone()) {
                if (writeFuture.isSuccess()) {
                    debug("Successfully sent re-auth request to the pipline: {}", reauthRequest);
                } else {
                    ctx.channel().pipeline().fireExceptionCaught(writeFuture.cause());
                }
            } else {
                writeFuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            }
            return null;
        }
        return decodeQueryPacket(ctx, request, packet);
    }

    /**
     * <p>decodeQueryPacket.</p>
     *
     * @param ctx
     *         a {@link io.netty.channel.ChannelHandlerContext} object
     * @param request
     *         a T object
     * @param msg
     *         a {@link com.ibasco.agql.protocols.valve.source.query.common.packets.SourceQuerySinglePacket} object
     *
     * @return a {@link java.lang.Object} object
     *
     * @throws java.lang.Exception
     *         if any.
     */
    abstract protected Object decodeQueryPacket(ChannelHandlerContext ctx, T request, SourceQuerySinglePacket msg) throws Exception;
}
