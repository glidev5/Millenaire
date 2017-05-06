package org.millenaire.blocks;

import java.util.List;
import java.util.Random;

import org.millenaire.Millenaire;
import org.millenaire.items.MillItems;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IStringSerializable;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class BlockMillOre extends Block {

	private EnumMillOre oreType;
	
	public BlockMillOre(EnumMillOre oretype) {
		super(Material.rock);
		this.oreType = oretype;
		this.setCreativeTab(Millenaire.tabMillenaire);
		this.setHarvestLevel("pickaxe", oretype.harvestLevel);
	}
	
	@Override
	public Item getItemDropped(IBlockState state, Random rand, int fortune) {
		return oreType.itemDropped;
	}
	
	@Override
	public int quantityDropped(Random rand) {
		return 2;
	}
    
    //////////////////////////////////////////////////////////\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\
    
    //TODO: registering
    
    public static Block galianiteOre;
    
    public static void preinitialize() {
    	galianiteOre = new BlockMillOre(EnumMillOre.GALIANITE).setUnlocalizedName("galianiteOre");
    	GameRegistry.registerBlock(galianiteOre, "galianiteOre");
    }
    
    public static void render() {
    	RenderItem renderItem = Minecraft.getMinecraft().getRenderItem();

		renderItem.getItemModelMesher().register(Item.getItemFromBlock(galianiteOre), 0, new ModelResourceLocation(Millenaire.MODID + ":galianiteOre", "inventory"));
    }
	
	public static enum EnumMillOre implements IStringSerializable {
		GALIANITE(0, "galianite", 2, 2, MillItems.galianiteDust);

		int harvestLevel;
		/**Currently unused, defaults to 2*/
		int maxDropped;
		Item itemDropped;
		private static final EnumMillOre[] META_LOOKUP = new EnumMillOre[values().length];
        private final int meta;
        private final String name;
		
		EnumMillOre(int meta, String name, int tool, int maxDropped, Item item) {
			this.meta = meta;
			this.name = name;
			this.harvestLevel = tool;
			this.maxDropped = maxDropped;
			this.itemDropped = item;
		}

        public int getMetadata()
        {
            return this.meta;
        }

        public String toString()
        {
            return this.name;
        }

        public static EnumMillOre byMetadata(int meta)
        {
            if (meta < 0 || meta >= META_LOOKUP.length)
            {
                meta = 0;
            }

            return META_LOOKUP[meta];
        }

        public String getName()
        {
            return this.name;
        }

        public String getUnlocalizedName()
        {
            return this.name;
        }

        static
        {
        	EnumMillOre[] var0 = values();
            int var1 = var0.length;

            for (int var2 = 0; var2 < var1; ++var2)
            {
            	EnumMillOre var3 = var0[var2];
                META_LOOKUP[var3.getMetadata()] = var3;
            }
        }
		
	}
}