package com.boydti.fawe.forge;


import com.boydti.fawe.Fawe;
import com.boydti.fawe.IFawe;
import com.boydti.fawe.forge.v1710.ForgeQueue_All;
import com.boydti.fawe.object.FaweCommand;
import com.boydti.fawe.object.FawePlayer;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.regions.FaweMaskManager;
import com.boydti.fawe.util.MainUtil;
import com.boydti.fawe.util.TaskManager;
import com.boydti.fawe.wrappers.WorldWrapper;
import com.mojang.authlib.GameProfile;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.forge.ForgeWorld;
import com.sk89q.worldedit.internal.LocalWorldAdapter;
import com.sk89q.worldedit.world.World;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.ModMetadata;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.management.InstanceAlreadyExistsException;
import net.minecraft.command.ServerCommandManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import org.apache.logging.log4j.Logger;

public class FaweForge implements IFawe {

    private final ForgeMain parent;
    private final File directory;
    private final Logger logger;
    private final ModMetadata mod;

    public FaweForge(ForgeMain plugin, Logger logger, ModMetadata mod, File directory) {
        this.parent = plugin;
        this.logger = logger;
        this.directory = directory;
        this.mod = mod;
        try {
            Fawe.set(this);
            setupInjector();
        } catch (InstanceAlreadyExistsException e) {
            MainUtil.handleError(e);
        }
    }

    public void setupInjector() {
        try {
            Fawe.setupInjector();
            com.sk89q.worldedit.forge.ForgePlayer.inject();
        } catch (Throwable e) {
            Fawe.debug("Failed to inject WorldEdit classes.");
        }
    }

    @Override
    public void debug(String s) {
        logger.debug(s);
    }

    @Override
    public boolean isOnlineMode() {
        return MinecraftServer.getServer().isServerInOnlineMode();
    }

    @Override
    public String getPlatformVersion() {
        return MinecraftServer.getServer().getMinecraftVersion();
    }

    @Override
    public int getPlayerCount() {
        return MinecraftServer.getServer().getCurrentPlayerCount();
    }

    @Override
    public File getDirectory() {
        return directory;
    }

    private HashMap<String, FaweCommand> commands = new HashMap<>();

    @Override
    public void setupCommand(String label, FaweCommand cmd) {
        this.commands.put(label, cmd);
    }

    public void insertCommands() {
        for (Map.Entry<String, FaweCommand> entry : commands.entrySet()) {
            ServerCommandManager scm = (ServerCommandManager) FMLCommonHandler.instance().getMinecraftServerInstance().getCommandManager();
            scm.registerCommand(new ForgeCommand(entry.getKey(), entry.getValue()));
        }
    }

    @Override
    public FawePlayer wrap(Object obj) {
        EntityPlayerMP player = null;
        if (obj instanceof String) {
            MinecraftServer server = MinecraftServer.getServer();
            player = server.getConfigurationManager().func_152612_a((String) obj);
        } else if (obj instanceof UUID) {
            player = getPlayerByUUID((UUID) obj);
        } else if (obj instanceof EntityPlayerMP) {
            player = (EntityPlayerMP) obj;
        }
        if (player == null) {
            return null;
        }
        FawePlayer existing = Fawe.get().getCachedPlayer(player.getCommandSenderName());
        return existing != null ? existing : new ForgePlayer(player);
    }

    @Override
    public void setupVault() {
        // Do nothing
    }

    @Override
    public TaskManager getTaskManager() {
        return new ForgeTaskMan(512);
    }

    @Override
    public String getWorldName(World world) {
        if (world instanceof WorldWrapper) {
            return getWorldName(((WorldWrapper) world).getParent());
        }
        else if (world instanceof EditSession) {
            return getWorldName(((EditSession) world).getWorld());
        } else if (world.getClass().getName().equals("com.sk89q.worldedit.bukkit.BukkitWorld")) {
            try {
                Class<?> classBukkitWorld = world.getClass();
                Method methodGetWorld = classBukkitWorld.getDeclaredMethod("getWorld");
                methodGetWorld.setAccessible(true);
                Object craftWorld = methodGetWorld.invoke(world);
                Class<? extends Object> classCraftWorld = craftWorld.getClass();
                Method methodGetHandle = classCraftWorld.getDeclaredMethod("getHandle");
                methodGetHandle.setAccessible(true);
                Object nmsWorld = methodGetHandle.invoke(craftWorld);
                return getWorldName((net.minecraft.world.World) nmsWorld);
            } catch (Throwable e) {
                e.printStackTrace();
                return world.getName();
            }
        }else if (world instanceof LocalWorldAdapter){
            return world.getName();
        }
        return getWorldName(((ForgeWorld) world).getWorld());
    }

    public String getWorldName(net.minecraft.world.World w) {
        Integer[] ids = DimensionManager.getIDs();
        WorldServer[] worlds = DimensionManager.getWorlds();
        for (int i = 0; i < ids.length; i++) {
            if (worlds[i] == w) {
                return w.getWorldInfo().getWorldName() + ";" + ids[i];
            }
        }
        return w.getWorldInfo().getWorldName() + ";" + w.provider.dimensionId;
    }

    @Override
    public FaweQueue getNewQueue(World world, boolean dontCareIfFast) {
        return new ForgeQueue_All(world);
    }

    @Override
    public FaweQueue getNewQueue(String world, boolean dontCareIfFast) {
        return new ForgeQueue_All(world);
    }

    @Override
    public Collection<FaweMaskManager> getMaskManagers() {
        return new ArrayList<>();
    }

    @Override
    public void startMetrics(){}

    @Override
    public String getPlatform() {
        return "forge";
    }

    @Override
    public UUID getUUID(String name) {
        try {
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public String getName(UUID uuid) {
        try {
            EntityPlayerMP playerMP = getPlayerByUUID(uuid);
            if(playerMP == null)
                return null;
            return playerMP.getCommandSenderName();
        } catch (Throwable e) {
            return null;
        }
    }

    public EntityPlayerMP getPlayerByUUID(UUID uuid) {
        for (Object playerObj : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            if (playerObj instanceof EntityPlayerMP) {
                EntityPlayerMP player = (EntityPlayerMP) playerObj;
                if (player.getUniqueID().equals(uuid)) {
                    return player;
                }
            }
        }
        return null;
    }

    @Override
    public Object getBlocksHubApi() {
        return null;
    }
}
