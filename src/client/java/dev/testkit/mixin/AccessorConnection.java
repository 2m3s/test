package dev.testkit.mixin;

import io.netty.channel.Channel;

import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The connection's netty channel, which it keeps private and has no getter for on 26.1.2. For {@code Latency}. */
@Mixin(Connection.class)
public interface AccessorConnection {

    @Accessor("channel")
    Channel testkit$channel();
}
