/*
 * Copyright 2020 http4s.org
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

package org.playframework.netty.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Java bridge that provides access to methods on the package-private
 * {@link HttpStreamsHandler} class. Scala 3 enforces Java access modifiers at
 * runtime so a Scala subclass in another package cannot call
 * {@code super.write()} or {@code sentOutMessage()} directly.
 *
 * <p>By living inside {@code org.playframework.netty.http} this class has the
 * necessary package access, and exposes thin delegating methods that Scala code
 * can call without triggering an {@link IllegalAccessError}.
 */
public abstract class HttpStreamsServerHandlerBridge extends HttpStreamsServerHandler {

    /** Delegate to {@link HttpStreamsHandler#write}. */
    protected void superWrite(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
    }

    /** Expose {@link HttpStreamsHandler#sentOutMessage} for bookkeeping. */
    protected void notifySentOutMessage(ChannelHandlerContext ctx) {
        sentOutMessage(ctx);
    }
}
