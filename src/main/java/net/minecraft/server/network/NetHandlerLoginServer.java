package net.minecraft.server.network;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.login.INetHandlerLoginServer;
import net.minecraft.network.login.client.CPacketEncryptionResponse;
import net.minecraft.network.login.client.CPacketLoginStart;
import net.minecraft.network.login.server.SPacketDisconnect;
import net.minecraft.network.login.server.SPacketEnableCompression;
import net.minecraft.network.login.server.SPacketEncryptionRequest;
import net.minecraft.network.login.server.SPacketLoginSuccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.CryptManager;
import net.minecraft.util.ITickable;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.craftbukkit.util.Waitable;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;

public class NetHandlerLoginServer implements INetHandlerLoginServer, ITickable
{
    private static final AtomicInteger AUTHENTICATOR_THREAD_ID = new AtomicInteger(0);
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Random RANDOM = new Random();
    private final byte[] verifyToken = new byte[4];
    private final MinecraftServer server;
    public final NetworkManager networkManager;
    private LoginState currentLoginState = LoginState.HELLO;
    private int connectionTimer;
    private GameProfile loginGameProfile;
    private final String serverId = "";
    private SecretKey secretKey;
    private EntityPlayerMP player;

    public String hostname = "";

    public NetHandlerLoginServer(MinecraftServer serverIn, NetworkManager networkManagerIn)
    {
        this.server = serverIn;
        this.networkManager = networkManagerIn;
        RANDOM.nextBytes(this.verifyToken);
    }

    public void update()
    {
        if (this.currentLoginState == LoginState.READY_TO_ACCEPT)
        {
            this.tryAcceptPlayer();
        }
        else if (this.currentLoginState == LoginState.DELAY_ACCEPT)
        {
            EntityPlayerMP entityplayermp = this.server.getPlayerList().getPlayerByUUID(this.loginGameProfile.getId());

            if (entityplayermp == null)
            {
                this.currentLoginState = LoginState.READY_TO_ACCEPT;
                net.minecraftforge.fml.common.network.internal.FMLNetworkHandler.fmlServerHandshake(this.server.getPlayerList(), this.networkManager, this.player);
                this.player = null;
            }
        }

        if (this.connectionTimer++ == 600)
        {
            this.disconnect(new TextComponentTranslation("multiplayer.disconnect.slow_login", new Object[0]));
        }
    }

    // CraftBukkit start
    @Deprecated
    public void disconnect(String s) {
        try {
            ITextComponent ichatbasecomponent = new TextComponentTranslation(s);
            this.networkManager.sendPacket(new SPacketDisconnect(ichatbasecomponent));
            this.networkManager.closeChannel(ichatbasecomponent);
        } catch (Exception exception) {
            NetHandlerLoginServer.LOGGER.error("Error whilst disconnecting player", exception);
        }
    }
    // CraftBukkit end

    public void disconnect(ITextComponent reason)
    {
        try
        {
            LOGGER.info("Disconnecting {}: {}", this.getConnectionInfo(), reason.getUnformattedText());
            this.networkManager.sendPacket(new SPacketDisconnect(reason));
            this.networkManager.closeChannel(reason);
        }
        catch (Exception exception)
        {
            LOGGER.error("Error whilst disconnecting player", (Throwable)exception);
        }
    }

    // Spigot start
    public void initUUID()
    {
        UUID uuid;
        if ( networkManager.spoofedUUID != null )
        {
            uuid = networkManager.spoofedUUID;
        } else
        {
            uuid = UUID.nameUUIDFromBytes( ( "OfflinePlayer:" + this.loginGameProfile.getName() ).getBytes( StandardCharsets.UTF_8 ) );
        }

        this.loginGameProfile = new GameProfile( uuid, this.loginGameProfile.getName() );

        if (networkManager.spoofedProfile != null)
        {
            for ( com.mojang.authlib.properties.Property property : networkManager.spoofedProfile )
            {
                this.loginGameProfile.getProperties().put( property.getName(), property );
            }
        }

        this.loginGameProfile = new GameProfile( uuid, this.loginGameProfile.getName() );
    }
    // Spigot end

    public void tryAcceptPlayer()
    {
        // Spigot start - Moved to initUUID
        /*
        if (!this.loginGameProfile.isComplete())
        {
            this.loginGameProfile = this.getOfflineProfile(this.loginGameProfile);
        }
        */
        // Spigot end

        // String s = this.server.getPlayerList().allowUserToConnect(this.networkManager.getRemoteAddress(), this.loginGameProfile);
        // CraftBukkit start - fire PlayerLoginEvent
        EntityPlayerMP s = this.server.getPlayerList().allowUserToConnect(this, this.loginGameProfile, hostname);

        if (s == null)
        {
            // this.disconnect(new TextComponentTranslation(s, new Object[0]));
        }
        else
        {
            this.currentLoginState = LoginState.ACCEPTED;

            if (this.server.getNetworkCompressionThreshold() >= 0 && !this.networkManager.isLocalChannel())
            {
                this.networkManager.sendPacket(new SPacketEnableCompression(this.server.getNetworkCompressionThreshold()), new ChannelFutureListener()
                {
                    public void operationComplete(ChannelFuture p_operationComplete_1_) throws Exception
                    {
                        NetHandlerLoginServer.this.networkManager.setCompressionThreshold(NetHandlerLoginServer.this.server.getNetworkCompressionThreshold());
                    }
                });
            }

            this.networkManager.sendPacket(new SPacketLoginSuccess(this.loginGameProfile));
            EntityPlayerMP entityplayermp = this.server.getPlayerList().getPlayerByUUID(this.loginGameProfile.getId());

            if (entityplayermp != null)
            {
                this.currentLoginState = LoginState.DELAY_ACCEPT;
                this.player = this.server.getPlayerList().createPlayerForUser(this.loginGameProfile, s);
            }
            else
            {
                net.minecraftforge.fml.common.network.internal.FMLNetworkHandler.fmlServerHandshake(this.server.getPlayerList(), this.networkManager, this.server.getPlayerList().createPlayerForUser(this.loginGameProfile, s));
            }
        }
    }

    public void onDisconnect(ITextComponent reason)
    {
        LOGGER.info("{} lost connection: {}", this.getConnectionInfo(), reason.getUnformattedText());
    }

    public String getConnectionInfo()
    {
        return this.loginGameProfile != null ? this.loginGameProfile + " (" + this.networkManager.getRemoteAddress() + ")" : String.valueOf((Object)this.networkManager.getRemoteAddress());
    }

    public void processLoginStart(CPacketLoginStart packetIn)
    {
        Validate.validState(this.currentLoginState == LoginState.HELLO, "Unexpected hello packet");
        this.loginGameProfile = packetIn.getProfile();

        if (this.server.isServerInOnlineMode() && !this.networkManager.isLocalChannel())
        {
            this.currentLoginState = LoginState.KEY;
            this.networkManager.sendPacket(new SPacketEncryptionRequest("", this.server.getKeyPair().getPublic(), this.verifyToken));
        }
        else
        {
            // Spigot start
            new Thread(net.minecraftforge.fml.common.thread.SidedThreadGroups.SERVER, "User Authenticator #" + NetHandlerLoginServer.AUTHENTICATOR_THREAD_ID.incrementAndGet()) {
                @Override
                public void run() {
                    try {
                        initUUID();
                        new LoginHandler().fireEvents();
                    } catch (Exception ex) {
                        disconnect("Failed to verify username!");
                        server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + loginGameProfile.getName(), ex);
                    }
                }
            }.start();
            // Spigot end
        }
    }

    public void processEncryptionResponse(CPacketEncryptionResponse packetIn)
    {
        Validate.validState(this.currentLoginState == LoginState.KEY, "Unexpected key packet");
        PrivateKey privatekey = this.server.getKeyPair().getPrivate();

        if (!Arrays.equals(this.verifyToken, packetIn.getVerifyToken(privatekey)))
        {
            throw new IllegalStateException("Invalid nonce!");
        }
        else
        {
            this.secretKey = packetIn.getSecretKey(privatekey);
            this.currentLoginState = LoginState.AUTHENTICATING;
            this.networkManager.enableEncryption(this.secretKey);
            (new Thread(net.minecraftforge.fml.common.thread.SidedThreadGroups.SERVER, "User Authenticator #" + AUTHENTICATOR_THREAD_ID.incrementAndGet())
            {
                public void run()
                {
                    GameProfile gameprofile = NetHandlerLoginServer.this.loginGameProfile;

                    try
                    {
                        String s = (new BigInteger(CryptManager.getServerIdHash("", NetHandlerLoginServer.this.server.getKeyPair().getPublic(), NetHandlerLoginServer.this.secretKey))).toString(16);
                        NetHandlerLoginServer.this.loginGameProfile = NetHandlerLoginServer.this.server.getMinecraftSessionService().hasJoinedServer(new GameProfile((UUID)null, gameprofile.getName()), s, this.getAddress());

                        if (NetHandlerLoginServer.this.loginGameProfile != null)
                        {
                            // CraftBukkit start - fire PlayerPreLoginEvent
                            if (!networkManager.isChannelOpen()) {
                                return;
                            }

                            new LoginHandler().fireEvents();
                        }
                        else if (NetHandlerLoginServer.this.server.isSinglePlayer())
                        {
                            NetHandlerLoginServer.LOGGER.warn("Failed to verify username but will let them in anyway!");
                            NetHandlerLoginServer.this.loginGameProfile = NetHandlerLoginServer.this.getOfflineProfile(gameprofile);
                            NetHandlerLoginServer.this.currentLoginState = LoginState.READY_TO_ACCEPT;
                        }
                        else
                        {
                            NetHandlerLoginServer.this.disconnect(new TextComponentTranslation("multiplayer.disconnect.unverified_username", new Object[0]));
                            NetHandlerLoginServer.LOGGER.error("Username '{}' tried to join with an invalid session", (Object)gameprofile.getName());
                        }
                    }
                    catch (AuthenticationUnavailableException var3)
                    {
                        if (NetHandlerLoginServer.this.server.isSinglePlayer())
                        {
                            NetHandlerLoginServer.LOGGER.warn("Authentication servers are down but will let them in anyway!");
                            NetHandlerLoginServer.this.loginGameProfile = NetHandlerLoginServer.this.getOfflineProfile(gameprofile);
                            NetHandlerLoginServer.this.currentLoginState = LoginState.READY_TO_ACCEPT;
                        }
                        else
                        {
                            NetHandlerLoginServer.this.disconnect(new TextComponentTranslation("multiplayer.disconnect.authservers_down", new Object[0]));
                            NetHandlerLoginServer.LOGGER.error("Couldn't verify username because servers are unavailable");

                        }
                        // CraftBukkit start - catch all exceptions
                    } catch (Exception exception) {
                        disconnect("Failed to verify username!");
                        server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + gameprofile.getName(), exception);
                        // CraftBukkit end
                    }
                }
                @Nullable
                private InetAddress getAddress()
                {
                    SocketAddress socketaddress = NetHandlerLoginServer.this.networkManager.getRemoteAddress();
                    return NetHandlerLoginServer.this.server.getPreventProxyConnections() && socketaddress instanceof InetSocketAddress ? ((InetSocketAddress)socketaddress).getAddress() : null;
                }
            }).start();
        }
    }

    // Spigot start
    public class LoginHandler {
        public void fireEvents() throws Exception {
            String playerName = loginGameProfile.getName();
            java.net.InetAddress address = ((java.net.InetSocketAddress) networkManager.getRemoteAddress()).getAddress();
            java.util.UUID uniqueId = loginGameProfile.getId();
            final org.bukkit.craftbukkit.CraftServer server = NetHandlerLoginServer.this.server.server;

            AsyncPlayerPreLoginEvent asyncEvent = new AsyncPlayerPreLoginEvent(playerName, address, uniqueId);
            server.getPluginManager().callEvent(asyncEvent);

            if (PlayerPreLoginEvent.getHandlerList().getRegisteredListeners().length != 0) {
                final PlayerPreLoginEvent event = new PlayerPreLoginEvent(playerName, address, uniqueId);
                if (asyncEvent.getResult() != PlayerPreLoginEvent.Result.ALLOWED) {
                    event.disallow(asyncEvent.getResult(), asyncEvent.getKickMessage());
                }
                Waitable<PlayerPreLoginEvent.Result> waitable = new Waitable<PlayerPreLoginEvent.Result>() {
                    @Override
                    protected PlayerPreLoginEvent.Result evaluate() {
                        server.getPluginManager().callEvent(event);
                        return event.getResult();
                    }};

                    NetHandlerLoginServer.this.server.processQueue.add(waitable);
                    if (waitable.get() != PlayerPreLoginEvent.Result.ALLOWED) {
                        disconnect(event.getKickMessage());
                        return;
                    }
            } else {
                if (asyncEvent.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                    disconnect(asyncEvent.getKickMessage());
                    return;
                }
            }
            // CraftBukkit end
            NetHandlerLoginServer.LOGGER.info("UUID of player {} is {}", NetHandlerLoginServer.this.loginGameProfile.getName(), NetHandlerLoginServer.this.loginGameProfile.getId());
            NetHandlerLoginServer.this.currentLoginState = NetHandlerLoginServer.LoginState.READY_TO_ACCEPT;
        }
    }
    // Spigot end

    protected GameProfile getOfflineProfile(GameProfile original)
    {
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + original.getName()).getBytes(StandardCharsets.UTF_8));
        return new GameProfile(uuid, original.getName());
    }

    static enum LoginState
    {
        HELLO,
        KEY,
        AUTHENTICATING,
        READY_TO_ACCEPT,
        DELAY_ACCEPT,
        ACCEPTED;
    }
}