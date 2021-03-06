package org.millenaire;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.millenaire.blocks.MillBlocks;
import org.millenaire.blocks.StoredPosition;
import org.millenaire.building.Building;
import org.millenaire.building.BuildingLocation;
import org.millenaire.building.BuildingTypes;
import org.millenaire.building.PlanIO;
import org.millenaire.networking.PacketShowBuildPoints;
import org.millenaire.village.Village;
import org.millenaire.village.VillageTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

public class MillCommand extends CommandBase {
    @Override
    public int compareTo(ICommand arg0) {
        return 0;
    }

    @Override
    public String getCommandName() {
        return "mill";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "mill <villages, loneBuildings, showBuildPoints>";
    }

    @Override
    public List<String> getCommandAliases() {
        return new ArrayList<String>() {{
            add("mill");
        }};
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.addChatMessage(new ChatComponentText("invalid argument: use villages, loneBuildings, or showBuildPoints"));
            return;
        }

        if (args[0].equalsIgnoreCase("village")) {
            //Spit out direction and distance to all villages

            //test code. remove before command use.
            for (Entry ent : BuildingTypes.getCache().entrySet()) {
                sender.addChatMessage(new ChatComponentText(ent.getKey() + " - " + ent.getValue()));
            }
        } else if (args[0].equalsIgnoreCase("loneBuildings")) {
            //Spit out Distance and direction to all lone buildings
        } else if (args[0].equalsIgnoreCase("showBuildPoints")) {
            if (((StoredPosition) MillBlocks.storedPosition).getShowParticles()) {
                ((StoredPosition) MillBlocks.storedPosition).setShowParticles(false);
            } else {
                ((StoredPosition) MillBlocks.storedPosition).setShowParticles(true);
            }

            Millenaire.simpleNetworkWrapper.sendTo(new PacketShowBuildPoints(((StoredPosition) MillBlocks.storedPosition).getShowParticles()), (EntityPlayerMP) sender);
        } else if (args[0].equalsIgnoreCase("spawn")) {
            if (args.length < 3) return;

            try {
                String culture = args[1].split(":")[0];
                String buildingID = args[1].split(":")[1];
                int level = Integer.parseInt(args[2]);
                EnumFacing direction = EnumFacing.EAST;
                boolean flatten = false;

                if (args.length > 3) {
                    direction = EnumFacing.byName(args[3]);
                    if (direction == EnumFacing.DOWN || direction == EnumFacing.UP) return;
                }
                if (args.length > 4) {
                    flatten = Boolean.parseBoolean(args[4]);
                }

                Building building = PlanIO.loadSchematic(PlanIO.getBuildingTag(buildingID, MillCulture.getCulture(culture), true), MillCulture.getCulture(culture), level, args[1]);

                if(building == null) {
                    sender.addChatMessage(new ChatComponentText("Building (or building level) not found."));
                    return;
                }

                if (flatten) {
                    Village v = VillageTracker.get(sender.getEntityWorld()).getVillageAt(sender.getPosition());
                    if(v == null) {
                        sender.addChatMessage(new ChatComponentText("Terrain flattening can only be used within the bounds of a village."));
                        return;
                    }

                    sender.addChatMessage(new ChatComponentText("Flattening terrain..."));
                    PlanIO.flattenTerrainForBuilding(building, new BuildingLocation(building, sender.getPosition(), direction),
                            v.geography);
                }

                sender.addChatMessage(new ChatComponentText("Spawning a level " + level + " " + buildingID + " facing " + direction.getName()));
                PlanIO.placeBuilding(building, new BuildingLocation(building, sender.getPosition(), direction), sender.getEntityWorld());
            } catch (Exception e) {
                sender.addChatMessage(new ChatComponentText(e.toString()));
                e.printStackTrace();
            }
        } else if(args[0].equalsIgnoreCase("top")) {
            Village v = VillageTracker.get(sender.getEntityWorld()).getVillageAt(sender.getPosition());
            if(v == null) {
                sender.addChatMessage(new ChatComponentText("Must be in a village"));
                return;
            }

            sender.addChatMessage(new ChatComponentText(v.geography.topGround
                    [sender.getPosition().getX() - v.geography.mapStartX]
                    [sender.getPosition().getZ() - v.geography.mapStartZ] + ""));
        }
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender.getCommandSenderEntity() instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) sender.getCommandSenderEntity();

            return FMLCommonHandler.instance().getMinecraftServerInstance().getConfigurationManager().canSendCommands((player).getGameProfile());
        }

        return false;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        return getListOfStringsMatchingLastWord(args, "village", "loneBuildings", "showBuildPoints", "spawn", "top");
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }
}
