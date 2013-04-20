package org.millenaire.common;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.Vector;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityDispenser;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.feature.WorldGenForest;
import net.minecraft.world.gen.feature.WorldGenTaiga2;
import net.minecraft.world.gen.feature.WorldGenTrees;
import net.minecraft.world.gen.feature.WorldGenerator;

import org.millenaire.common.MLN.MillenaireException;
import org.millenaire.common.MillVillager.InvItem;
import org.millenaire.common.MillWorldInfo.MillMapInfo;
import org.millenaire.common.construction.BuildingPlan;
import org.millenaire.common.construction.BuildingPlan.BuildingBlock;
import org.millenaire.common.construction.BuildingPlan.LocationBuildingPair;
import org.millenaire.common.construction.BuildingPlan.StartingGood;
import org.millenaire.common.construction.BuildingPlanSet;
import org.millenaire.common.construction.BuildingProject;
import org.millenaire.common.core.MillCommonUtilities;
import org.millenaire.common.forge.BuildingChunkLoader;
import org.millenaire.common.forge.Mill;
import org.millenaire.common.forge.MillAchievements;
import org.millenaire.common.goal.Goal;
import org.millenaire.common.item.Goods;
import org.millenaire.common.network.ServerReceiver;
import org.millenaire.common.network.ServerSender;
import org.millenaire.common.network.StreamReadWrite;
import org.millenaire.common.pathing.AStarPathing;
import org.millenaire.common.pathing.AStarPathing.PathingWorker;
import org.millenaire.common.pathing.AStarPathing.Point2D;
import org.millenaire.common.pathing.PathingBinary;
import org.millenaire.common.pathing.atomicstryker.AStarConfig;
import org.millenaire.common.pathing.atomicstryker.AStarNode;
import org.millenaire.common.pathing.atomicstryker.AStarPathPlanner;
import org.millenaire.common.pathing.atomicstryker.IAStarPathedEntity;

public class Building {

	private class PathCreator implements IAStarPathedEntity {

		final PathCreatorInfo info;
		final InvItem pathConstructionGood;
		final int pathWidth;
		final Building destination;

		PathCreator(PathCreatorInfo info,InvItem pathConstructionGood,int pathWidth,Building destination) {
			this.pathConstructionGood=pathConstructionGood;
			this.pathWidth=pathWidth;
			this.destination=destination;
			this.info=info;
		}

		private void checkForRebuild() {
			if (info.nbPathsReceived==info.nbPathsExpected) {

				//so brand-new paths get built first
				Collections.reverse(info.pathsReceived);

				pathsToBuild=info.pathsReceived;
				pathsToBuildIndex=0;
				pathsToBuildPathIndex=0;

				calculatePathsToClear();

				pathsChanged=true;
				info.pathsReceived=null;

				info.creationComplete=true;
			}
		}

		@Override
		public void onFoundPath(ArrayList<AStarNode> result) {

			if (info.creationComplete) {
				MLN.error(Building.this, "onFoundPath triggered on completed info object.");
				return;
			}

			//	pathsReceived.add(MillCommonUtilities.buildPath(Building.this, result,Block.cloth.blockID,MillCommonUtilities.randomInt(16),pathWidth));
			info.pathsReceived.add(MillCommonUtilities.buildPath(Building.this, result,pathConstructionGood.id(),pathConstructionGood.meta,pathWidth));
			info.nbPathsReceived++;

			checkForRebuild();
		}

		@Override
		public void onNoPathAvailable() {

			if (info.creationComplete) {
				MLN.error(Building.this, "onNoPathAvailable triggered on completed info object.");
				return;
			}

			info.nbPathsReceived++;
			if (MLN.LogVillagePaths>=MLN.MINOR) {
				MLN.minor(Building.this, "Path calculation failed. Target: "+destination);
			}

			checkForRebuild();
		}

	}

	private class PathCreatorInfo {
		private final int nbPathsExpected;
		private int nbPathsReceived=0;
		private Vector<Vector<BuildingBlock>> pathsReceived=new Vector<Vector<BuildingBlock>>();

		private boolean creationComplete=false;

		PathCreatorInfo(int nbPathsExpected) {
			this.nbPathsExpected=nbPathsExpected;
		}

	}



	public class PathingThread extends Thread {

		MillWorldInfo winfo;

		public PathingThread(MillWorldInfo wi) {
			winfo = wi;
		}

		@Override
		public void run() {
			final AStarPathing temp = new AStarPathing();
			if (MLN.LogPathing >= MLN.MAJOR) {
				MLN.major(this, "Start");
			}
			final long tm = System.currentTimeMillis();
			try {
				if (temp.createConnectionsTable(winfo,sleepingPos)) {
					pathing = temp;
					lastPathingUpdate = System.currentTimeMillis();
				} else {
					lastPathingUpdate = System.currentTimeMillis();
					pathing = null;
				}
			} catch (final MillenaireException e) {
				MLN.printException(e);
			}
			if (MLN.LogPathing >= MLN.MAJOR) {
				MLN.major(this, "Done: "
						+ (((System.currentTimeMillis() - tm) * 1.0) / 1000));
			}
			rebuildingPathing = false;
		}
	}

	public static class PerformanceMonitor {

		public HashMap<String, Double> goalTime = new HashMap<String, Double>();

		public int nbPathing, nbCached, nbPartlyCached, nbReused;
		public double totalTime, pathingTime, noPathFoundTime;

		Building townHall;

		PerformanceMonitor(Building townHall) {
			this.townHall = townHall;
			reset();
		}

		public void addToGoal(String key, double time) {

			totalTime += time;

			if (goalTime.containsKey(Goal.goals.get(key))) {
				time = goalTime.get((Goal.goals.get(key))) + time;
			}
			goalTime.put(key, time);
		}

		public String getStats() {

			if (townHall.pathing == null)
				return null;

			String s = Math.round(totalTime)
					+ "/"
					+ Math.round(pathingTime)
					+ "/"
					+ Math.round(noPathFoundTime)
					+ " - "
					+ nbPathing
					+ "("
					+ ((Math.round((nbReused * 10000) / (nbPathing + 1)) * 1.0f) / 100)
					+ "% / "
					+ ((Math.round((nbCached * 10000) / (nbPathing + 1)) * 1.0f) / 100)
					+ "% / "
					+ ((Math.round((nbPartlyCached * 10000) / (nbPathing + 1)) * 1.0f) / 100)
					+ "%) Nb cached: " + townHall.pathing.cache.size() + " ";
			for (final String goalKey : goalTime.keySet()) {
				if (Goal.goals.containsKey(goalKey)) {
					s += Goal.goals.get(goalKey).gameName(null) + ": "
							+ goalTime.get(goalKey) + " ";
				} else {
					s += "No goal: " + goalTime.get(goalKey) + " ";
				}
			}

			for (int i = 0; i < townHall.pathing.firstDemand.size(); i++) {
				// s+=townHall.pathing.firstDemand.get(i)+": "+townHall.pathing.firstDemandOutcome.get(i)+"      ";
			}

			return s;
		}

		public void reset() {
			goalTime = new HashMap<String, Double>();
			totalTime = 0;
			pathingTime = 0;
			nbPathing = 0;
			nbCached = 0;
			noPathFoundTime = 0;
			nbPartlyCached = 0;
			nbReused = 0;
		}
	}

	private class SaveWorker extends Thread {

		private final String reason;

		private SaveWorker(String reason) {
			this.reason=reason;
		}

		@Override
		public void run() {
			if (!isTownhall)
				return;

			final long startTime = System.currentTimeMillis();

			final NBTTagCompound mainTag = new NBTTagCompound();

			NBTTagList nbttaglist;

			nbttaglist = new NBTTagList();
			for (int i=0;i<buildings.size();i++ ) {
				final Point p=buildings.get(i);
				if (p != null) {
					final Building b = mw.getBuilding(p);
					if (b != null) {
						final NBTTagCompound buildingTag = new NBTTagCompound();
						b.writeToNBT(buildingTag);
						nbttaglist.appendTag(buildingTag);
					}
				}
			}
			mainTag.setTag("buildings", nbttaglist);

			final File millenaireDir = mw.millenaireDir;

			if (!millenaireDir.exists()) {
				millenaireDir.mkdir();
			}

			final File buildingsDir = new File(millenaireDir, "buildings");

			if (!buildingsDir.exists()) {
				buildingsDir.mkdir();
			}

			final File file1 = new File(buildingsDir, getPos().getPathString()
					+ "_temp.gz");
			try {
				final FileOutputStream fileoutputstream = new FileOutputStream(
						file1);
				CompressedStreamTools.writeCompressed(mainTag,
						fileoutputstream);

				file1.renameTo(new File(buildingsDir, getPos().getPathString()
						+ ".gz"));
			} catch (final IOException e) {
				MLN.printException(e);
			}

			if (MLN.LogHybernation >= MLN.MAJOR) {
				MLN.major(Building.this, "Saved " + buildings.size() + " buildings in "
						+ (System.currentTimeMillis() - startTime) + " ms due to "
						+ reason + " (" + saveReason + ").");
			}

			lastSaved = worldObj.getWorldTime();
			saveNeeded = false;
			saveReason = null;

			saveWorker=null;
		}

	}
	public static final AStarConfig PATH_BUILDER_JPS_CONFIG=new AStarConfig(true,false,false,false);
	public static final int INVADER_SPAWNING_DELAY = 500;
	public static final int RELATION_FAIR = 10;
	public static final int RELATION_DECENT = 30;

	public static final int RELATION_GOOD = 50;
	public static final int RELATION_VERYGOOD = 70;
	public static final int RELATION_EXCELLENT = 90;
	public static final int RELATION_CHILLY = -10;
	public static final int RELATION_BAD = -30;

	public static final int RELATION_VERYBAD = -50;
	public static final int RELATION_ATROCIOUS = -70;

	public static final int RELATION_OPENCONFLICT = -90;
	public static final int RELATION_MAX = 100;
	public static final int RELATION_MIN = -100;
	public static final String blArmoury = "armoury";
	public static final String blBakery = "bakery";
	public static final String blCattleFarm = "cattlefarm";
	public static final String blChurch = "church";
	public static final String blFarm = "farm";
	public static final String blForge = "forge";
	public static final String blLumbermanhut = "lumbermanhut";
	public static final String blMarket = "market";
	public static final String blPigFarm = "pigfarm";
	public static final String blPresbytery = "presbytery";

	public static final String blSheepChickenFarm = "sheepchickenfarm";
	public static final String blTavern = "tavern";
	public static final String blTownhall = "townhall";
	public static final String blWatchtower = "watchtower";
	private static final int LOCATION_SEARCH_DELAY = 80000;
	public static final int MIN_REPUTATION_FOR_TRADE = -16 * 64;
	public static final int MAX_REPUTATION = 8 * 64 * 64;
	private static final int PATHING_REBUILD_DELAY = 1 * 60 * 1000;
	public static final String tagAlembic = "Alembic";
	public static final String tagArchives = "archives";
	public static final String tagBakery = "bakery";
	public static final String tagCattle = "cattle";
	public static final String tagCider = "cider";
	public static final String tagDrinking = "Drinking";
	public static final String tagFishingSpot = "fishingspot";
	public static final String tagGrove = "grove";
	public static final String tagInn = "inn";
	public static final String tagKiln = "brickkiln";
	public static final String tagMaizeFarm = "maizefarm";
	public static final String tagMarket = "market";
	public static final String tagOven = "Oven";
	public static final String tagPaddy = "paddy";
	public static final String tagPatrol = "Patrol";
	public static final String tagPigs = "pigs";
	public static final String tagChicken = "chicken";
	public static final String tagPraying = "Praying";
	public static final String tagSheeps = "sheeps";
	public static final String tagSpiceGarden = "spicegarden";
	public static final String tagSugarPlantation = "sugarplantation";
	public static final String tagHoF = "hof";
	public static final String tagPujas = "pujas";
	public static final String tagVineyard = "vineyard";
	public static final String tagSilkwormFarm = "silkwormfarm";
	public static final String tagDespawnAllMobs = "despawnallmobs";
	public static final String tagLeasure = "leasure";
	public static final String tagNoPaths = "nopaths";

	public static final String versionCompatibility = "1.0";

	public static void readBuildingPacket(MillWorld mw,DataInputStream ds) {

		Point pos=null;
		try {
			pos = StreamReadWrite.readNullablePoint(ds);
		} catch (final IOException e) {
			MLN.printException(e);
			return;
		}

		Building building=mw.getBuilding(pos);

		boolean newbuilding=false;

		if (building==null) {
			building=new Building(mw);
			newbuilding=true;
		}

		building.pos=pos;

		try {
			building.isTownhall=ds.readBoolean();
			building.chestLocked=ds.readBoolean();
			building.controlledBy=StreamReadWrite.readNullableString(ds);
			building.controlledByName=StreamReadWrite.readNullableString(ds);
			building.townHallPos=StreamReadWrite.readNullablePoint(ds);
			building.culture = Culture.getCultureByName(StreamReadWrite.readNullableString(ds));
			final String vtype=StreamReadWrite.readNullableString(ds);
			if ((building.culture!=null) && (building.culture.getVillageType(vtype)!=null)) {
				building.villageType=building.culture.getVillageType(vtype);
			} else if ((building.culture!=null) && (building.culture.getLoneBuildingType(vtype)!=null)) {
				building.villageType=building.culture.getLoneBuildingType(vtype);
			}

			building.location=StreamReadWrite.readNullableBuildingLocation(ds);


			building.buildingGoal=StreamReadWrite.readNullableString(ds);
			building.buildingGoalIssue=StreamReadWrite.readNullableString(ds);
			building.buildingGoalLevel=ds.readInt();
			building.buildingGoalVariation=ds.readInt();
			building.buildingGoalLocation=StreamReadWrite.readNullableBuildingLocation(ds);
			building.buildingLocationIP=StreamReadWrite.readNullableBuildingLocation(ds);
			building.buildingProjects=StreamReadWrite.readProjectVectorVector(ds, building.culture);

			building.chests=StreamReadWrite.readPointVector(ds);
			building.furnaces=StreamReadWrite.readPointVector(ds);
			building.signs=StreamReadWrite.readPointVector(ds);
			building.buildings=StreamReadWrite.readPointVector(ds);


			building.buildingsBought=StreamReadWrite.readStringVector(ds);

			building.relations=StreamReadWrite.readPointIntegerMap(ds);
			building.raidsPerformed=StreamReadWrite.readStringVector(ds);
			building.raidsSuffered=StreamReadWrite.readStringVector(ds);

			building.vrecords=StreamReadWrite.readVillagerRecordVector(mw,ds);

			building.pujas=StreamReadWrite.readOrUpdateNullablePuja(ds, building, building.pujas);

			building.visitorsList=StreamReadWrite.readStringVector(ds);
			building.imported=StreamReadWrite.readInventory(ds);
			building.exported=StreamReadWrite.readInventory(ds);

			building.name=StreamReadWrite.readNullableString(ds);
			building.qualifier=StreamReadWrite.readNullableString(ds);

			for (final Point p : building.chests) {
				final TileEntityMillChest chest=p.getMillChest(mw.world);
				if (chest!=null) {
					chest.buildingPos=building.getPos();
				}
			}

		} catch (final IOException e) {
			MLN.printException(e);
		}

		if (newbuilding) {
			mw.addBuilding(building, pos);
		}

		//MLN.temp(building, "Read building update");
	}

	private BuildingBlock[] bblocks = null;

	public int bblocksPos = 0;

	private boolean bblocksChanged=false,pathsChanged=false;
	private PathingBinary binaryPathing = null;
	public Vector<Point> brickspot = new Vector<Point>();
	public MillVillager builder = null;
	public String buildingGoal, buildingGoalIssue;
	public int buildingGoalLevel = 0;
	public BuildingLocation buildingGoalLocation = null;
	public int buildingGoalVariation = 0;
	public BuildingLocation buildingLocationIP = null;
	public Vector<Vector<BuildingProject>> buildingProjects = new Vector<Vector<BuildingProject>>();
	public Vector<Point> buildings = new Vector<Point>();
	public Vector<String> buildingsBought = new Vector<String>();
	public Vector<Point> chests = new Vector<Point>();
	public Culture culture;
	private boolean declaredPos = false;
	public HashMap<InvItem, Integer> exported = new HashMap<InvItem, Integer>();
	public Vector<Point> fishingspots = new Vector<Point>();
	public Vector<Point> sugarcanesoils = new Vector<Point>();
	public Vector<Point> healingspots = new Vector<Point>();
	public Vector<Point> furnaces = new Vector<Point>();
	public Vector<Point> brewingStands = new Vector<Point>();
	public HashMap<InvItem, Integer> imported = new HashMap<InvItem, Integer>();
	public boolean isActive=false,isAreaLoaded=false, chestLocked;
	public boolean isTownhall = false, isInn = false, isMarket = false;
	private long lastFailedOtherLocationSearch = 0;
	private long lastFailedProjectLocationSearch = 0;
	public long lastPathingUpdate;
	public long lastPing;
	private long lastSaved = 0;
	public long lastVillagerRecordsRepair = 0;

	public long lastWoodLocations = 0, lastPlantingLocations = 0,
			lastSignUpdate = 0;
	public BuildingLocation location;
	public VillagerRecord merchantRecord = null;
	public boolean metadataMismatch = false;
	public PerformanceMonitor monitor;
	private String name = null, qualifier = "";
	public int nbNightsMerchant = 0;
	private HashMap<Goods, Integer> neededGoodsCached = new HashMap<Goods, Integer>();
	private long neededGoodsLastGenerated = 0;
	public boolean thNightActionPerformed = false;
	public boolean noProjectsLeft = false;
	public AStarPathing pathing = null;
	public EntityPlayer closestPlayer=null;
	private Point pos = null;
	private boolean rebuildingPathing = false;
	private boolean saveNeeded = false;
	private String saveReason = null;
	public MillVillager seller = null;

	public Point sellingPlace = null;
	public Vector<Point> signs = new Vector<Point>();
	public Vector<Vector<Point>> sources = new Vector<Vector<Point>>();
	public Vector<Integer> sourceTypes = new Vector<Integer>();
	public Vector<Vector<Point>> spawns = new Vector<Vector<Point>>();
	public Vector<String> spawnTypes = new Vector<String>();
	public Vector<Vector<Point>> mobSpawners = new Vector<Vector<Point>>();
	public Vector<String> mobSpawnerTypes = new Vector<String>();
	public Vector<Vector<Point>> soils = new Vector<Vector<Point>>();
	public Vector<String> soilTypes = new Vector<String>();
	public Vector<Point> stalls = new Vector<Point>();

	public Vector<Point> woodspawn = new Vector<Point>();

	public Vector<Point> subBuildings = new Vector<Point>();
	public Vector<Point> netherwartsoils = new Vector<Point>();

	public Vector<Point> silkwormblock = new Vector<Point>();

	public Vector<Point> dispenderUnknownPowder = new Vector<Point>();
	private Point sleepingPos = null, sellingPos = null, craftingPos = null,
			defendingPos = null, shelterPos = null, pathStartPos=null, leasurePos=null;

	private Point townHallPos = null;
	public Vector<MillVillager> villagers = new Vector<MillVillager>();
	public Vector<String> visitorsList = new Vector<String>();

	public Vector<VillagerRecord> vrecords = new Vector<VillagerRecord>();

	public VillageType villageType = null;
	private HashMap<Point,Integer> relations = new HashMap<Point,Integer>();

	public Point parentVillage = null;
	public MillWorldInfo winfo = new MillWorldInfo();
	public MillMapInfo mapInfo = null;
	public MillWorld mw;
	public World worldObj;
	private boolean nightBackgroundActionPerformed;

	private boolean updateRaidPerformed;
	public Vector<String> raidsPerformed = new Vector<String>();


	public Vector<String> raidsSuffered = new Vector<String>();
	public Point raidTarget;
	public long  raidStart=0;
	public long  raidPlanningStart;

	public boolean underAttack=false;
	private int nbAnimalsRespawned;

	public Puja pujas=null;
	public String controlledBy=null;

	public String controlledByName=null;
	private SaveWorker saveWorker=null;


	private long lastGoodsRefresh=0;

	private boolean refreshGoodsNightActionPerformed;

	private BuildingChunkLoader chunkLoader=null;

	public Vector<Vector<BuildingBlock>> pathsToBuild=null;

	public int pathsToBuildIndex=0,pathsToBuildPathIndex=0;

	public Vector<Point> oldPathPointsToClear=null;

	public int oldPathPointsToClearIndex=0;

	private boolean autobuildPaths=false;

	private Building(MillWorld mw) {
		this.mw = mw;
		worldObj=mw.world;
	}

	public Building(MillWorld mw, Culture c, VillageType villageType,
			BuildingLocation l, boolean townHall, boolean villageCreation,
			Point p, Point townHallPos) {

		pos = p;
		this.mw = mw;
		worldObj=mw.world;
		location = l;
		culture = c;
		this.villageType = villageType;

		if (worldObj == null) {
			final MillenaireException e = new MillenaireException(
					"Null worldObj!");
			MLN.printException(e);
		}
		if (this.pos == null) {
			final MillenaireException e = new MillenaireException("Null pos!");
			MLN.printException(e);
		}
		if (this.location == null) {
			final MillenaireException e = new MillenaireException(
					"Null location!");
			MLN.printException(e);
		}
		if (this.culture == null) {
			final MillenaireException e = new MillenaireException(
					"Null culture!");
			MLN.printException(e);
		}

		mw.addBuilding(this, p);

		if (MLN.DEV) {
			monitor = new PerformanceMonitor(this);
		}

		isTownhall = townHall;

		pathing = null;

		if (isTownhall) {
			this.townHallPos = getPos();
		} else {
			this.townHallPos = townHallPos;
		}

		location = l;
		this.isTownhall = townHall;

		if (l.tags.contains(tagInn) && !isTownhall) {
			isInn = true;
		}

		if ((l.tags.contains(tagMarket)) && !isTownhall) {
			isMarket = true;
		}

		if (l.tags.contains(tagPujas)) {
			pujas=new Puja(this);
		}
	}

	public Building(MillWorld mw,NBTTagCompound nbttagcompound) {
		this.mw = mw;
		worldObj=mw.world;
		readFromNBT(nbttagcompound);

		if (MLN.DEV) {
			monitor = new PerformanceMonitor(this);
		}

		if (this.pos == null) {
			final MillenaireException e = new MillenaireException("Null pos!");
			MLN.printException(e);
		}

		mw.addBuilding(this, pos);
	}

	public Building(MillWorld mw,NBTTagCompound nbttagcompound, Point pos) {
		this.mw = mw;
		worldObj=mw.world;
		readFromNBT(nbttagcompound);
		this.pos = pos;

		if (MLN.DEV) {
			monitor = new PerformanceMonitor(this);
		}

		if (this.pos == null) {
			final MillenaireException e = new MillenaireException("Null pos!");
			MLN.printException(e);
		}

		mw.addBuilding(this, pos);
	}

	public void addAdult(MillVillager child) throws MillenaireException {

		String type = "";

		final HashMap<String, Integer> villagerCount = new HashMap<String, Integer>();
		final HashMap<String, Integer> residentCount = new HashMap<String, Integer>();

		Vector<String> residents;

		if (child.gender == MillVillager.MALE) {
			residents = location.maleResident;
		} else {
			residents = location.femaleResident;
		}

		for (String s : residents) {
			if (MillVillager.oldVillagers.containsKey(type.toLowerCase())) {
				if (child.gender == MillVillager.MALE) {
					s = MillVillager.oldVillagers.get(type.toLowerCase())[0];
				} else {
					s = MillVillager.oldVillagers.get(type.toLowerCase())[1];
				}
			}
			if (residentCount.containsKey(s)) {
				residentCount.put(s, residentCount.get(s) + 1);
			} else {
				residentCount.put(s, 1);
			}
		}

		for (final VillagerRecord vr : vrecords) {
			if (villagerCount.containsKey(vr.type)) {
				villagerCount.put(vr.type, villagerCount.get(vr.type) + 1);
			} else {
				villagerCount.put(vr.type, 1);
			}
		}

		for (final String s : residentCount.keySet()) {
			if (!villagerCount.containsKey(s)) {
				type = s;
			} else if (villagerCount.get(s) < residentCount.get(s)) {
				type = s;
			}
		}

		child.getHouse().removeVillagerRecord(child.villager_id);

		if (MLN.LogWorldGeneration >= MLN.MAJOR) {
			MLN.major(this,
					"Creating " + type + " with child " + child.getName() + "/"
							+ child.villager_id);
		}
		final MillVillager adult = MillVillager.createVillager(culture, type,
				child.gender, worldObj, child.getPos(), getPos(), townHallPos, false,
				child.firstName, child.familyName);

		if (adult == null) {
			MLN.error(this, "Couldn't create adult of type " + type
					+ " from child " + child);
			return;
		}

		adult.villager_id = child.villager_id;

		VillagerRecord adultRecord = adult.getRecord();
		if (adultRecord == null) {
			adultRecord = new VillagerRecord(mw,adult);
		}
		adultRecord.updateRecord(adult);

		addOrReplaceVillager(adult);
		getTownHall().addOrReplaceVillager(adult);
		addOrReplaceRecord(adultRecord);
		getTownHall().addOrReplaceRecord(adultRecord);

		for (final VillagerRecord vr : vrecords) {
			if (vr.gender != adult.gender) {
				if (adult.gender == MillVillager.FEMALE) {//takes husband name
					adultRecord.maidenName = adultRecord.familyName;
					adultRecord.familyName = vr.familyName;
					adult.familyName = vr.familyName;
				}

				if (vr.gender == MillVillager.FEMALE) {//existing woman take new husband's name
					vr.maidenName = vr.familyName;
					vr.familyName = adult.familyName;
					final MillVillager spouse=this.getVillagerById(vr.id);
					if (spouse!=null) {
						spouse.familyName=vr.familyName;
					}
				}
				adultRecord.spousesName = vr.getName();
				vr.spousesName = adult.getName();
			}
		}

		child.despawnVillager();

		worldObj.spawnEntityInWorld(adult);

		if (isInn) {
			merchantCreated();
		} else {
			updateHouseSign();
		}
	}


	public void addMobSpawnerPoint(String type, Point p) {
		if (!mobSpawnerTypes.contains(type)) {
			final Vector<Point> spawnsPoint=new Vector<Point>();
			spawnsPoint.add(p);
			mobSpawners.add(spawnsPoint);
			mobSpawnerTypes.add(type);
		} else {
			for (int i=0;i<mobSpawnerTypes.size();i++) {
				if (mobSpawnerTypes.get(i).equals(type)) {
					if (!mobSpawners.get(i).contains(p)) {
						mobSpawners.get(i).add(p);
					}
				}
			}
		}
	}

	public void addOrReplaceRecord(VillagerRecord vr)
			throws MillenaireException {
		if (vr == null)
			throw new MillenaireException("Attempting to insert null VR");

		while (vrecords.remove(vr)) {
			;
		}
		vrecords.add(vr);
	}

	public void addOrReplaceVillager(MillVillager villager) {

		if (villager.vtype==null) {
			MLN.printException("Tried adding a villager, but his type is null: "+villager, new Exception());
			return;
		}


		for (int i = villagers.size() - 1; i >= 0; i--) {
			if (villagers.get(i).villager_id==villager.villager_id) {
				if (villagers.get(i) == villager) {//same object
					villagers.remove(i);
				} else if (villagers.get(i).entityId == villager.entityId) {
					if (MLN.LogVillager>=MLN.MAJOR) {
						MLN.major(villagers.get(i), "Two copies with same entityId!");
					}
					villagers.remove(i);
				} else {
					if (villager.client_lastupdated > villagers.get(i).client_lastupdated) {
						if (MLN.LogVillagerSpawn>=MLN.MINOR) {
							final Exception e=new Exception();
							MLN.printException("addOrReplaceVillager in "+this+": Found an other copy of " + villager+" in village "+villagers.get(i).getTownHall()+": "+villagers.get(i),e);
						}
						villagers.get(i).despawnVillagerSilent();
						villagers.remove(i);
					}
				}
			}
		}
		villagers.add(villager);
	}

	public void addSoilPoint(String type, Point p) {
		if (!soilTypes.contains(type)) {
			final Vector<Point> spawnsPoint=new Vector<Point>();
			spawnsPoint.add(p);
			soils.add(spawnsPoint);
			soilTypes.add(type);
		} else {
			for (int i=0;i<soilTypes.size();i++) {
				if (soilTypes.get(i).equals(type)) {
					if (!soils.get(i).contains(p)) {
						soils.get(i).add(p);
					}
				}
			}
		}
	}

	public void addSourcePoint(int blockId, Point p) {
		if (!sourceTypes.contains(blockId)) {
			final Vector<Point> spawnsPoint=new Vector<Point>();
			spawnsPoint.add(p);
			sources.add(spawnsPoint);
			sourceTypes.add(blockId);
		} else {
			for (int i=0;i<sourceTypes.size();i++) {
				if (sourceTypes.get(i).equals(blockId)) {
					if (!sources.get(i).contains(p)) {
						sources.get(i).add(p);
					}
				}
			}
		}
	}

	public void addSpawnPoint(String type, Point p) {
		if (!spawnTypes.contains(type)) {
			final Vector<Point> spawnsPoint=new Vector<Point>();
			spawnsPoint.add(p);
			spawns.add(spawnsPoint);
			spawnTypes.add(type);
		} else {
			for (int i=0;i<spawnTypes.size();i++) {
				if (spawnTypes.get(i).equals(type)) {
					if (!spawns.get(i).contains(p)) {
						spawns.get(i).add(p);
					}
				}
			}
		}
	}

	public void addToExports(InvItem good, int quantity) {
		if (exported.containsKey(good)) {
			exported.put(good, exported.get(good) + quantity);
		} else {
			exported.put(good, quantity);
		}
	}

	public void addToImports(InvItem good, int quantity) {
		if (imported.containsKey(good)) {
			imported.put(good, imported.get(good) + quantity);
		} else {
			imported.put(good, quantity);
		}
	}

	public void adjustLanguage(EntityPlayer player,int l) {
		mw.getProfile(player.username).adjustLanguage(getTownHall().culture.key,l);
	}

	public void adjustRelation(Point villagePos,int change) {
		//First change to local values
		int relation=change;

		if (relations.containsKey(villagePos)) {
			relation+=relations.get(villagePos);
		}

		if (relation>RELATION_MAX) {
			relation=RELATION_MAX;
		} else if (relation<RELATION_MIN) {
			relation=RELATION_MIN;
		}

		relations.put(villagePos, relation);

		saveNeeded=true;

		//then mirroring to other village's values
		final Building village=mw.getBuilding(villagePos);

		if (village==null) {
			MLN.error(this, "Could not find village at "+villagePos+" in order to adjust relation.");
		} else {
			village.relations.put(getPos(), relation);
			village.saveTownHall("distance relation change");//immediate save as the distance village might be frozen
		}
	}

	public void adjustReputation(EntityPlayer player,int l) {
		mw.getProfile(player.username).adjustReputation(getTownHall(), l);
	}

	public boolean areBlocksLeft() {
		if (bblocks==null)
			return false;

		if (bblocksPos>=bblocks.length)
			return false;

		return true;
	}

	public void attemptMerchantMove(boolean forced) {

		final Vector<Building> targets = new Vector<Building>();
		final Vector<Building> backupTargets = new Vector<Building>();

		for (final Point vp : getTownHall().relations.keySet()) {
			final Building townHall = mw.getBuilding(vp);
			if ((townHall != null) && (getTownHall() != null)
					&& (townHall.villageType != getTownHall().villageType)
					&& ((getTownHall().relations.get(vp)>=RELATION_EXCELLENT) || ((getTownHall().relations.get(vp)>=RELATION_GOOD) && (townHall.culture==culture)))
					&& (getPos().distanceTo(townHall.getPos()) < 2000)) {

				if (MLN.LogMerchant >= MLN.DEBUG) {
					MLN.debug(this, "Considering village "+townHall.getVillageQualifiedName()+" for merchant : "
							+ merchantRecord);
				}

				for (final Building inn : townHall
						.getBuildingsWithTag(tagInn)) {
					boolean moveNeeded = false;

					final HashMap<InvItem, Integer> content = getChestsContent();

					for (final InvItem good : content.keySet()) {
						if ((content.get(good) > 0)
								&& (inn.getTownHall().nbGoodNeeded(good.id(),
										good.meta) > 0)) {
							moveNeeded = true;
							break;
						}
					}

					if (moveNeeded) {
						if (inn.merchantRecord == null) {// higher change of
							// picking empty inn
							targets.add(inn);
							targets.add(inn);
							targets.add(inn);
						} else if ((inn.nbNightsMerchant > 1) || forced) {
							targets.add(inn);
						}

						if (MLN.LogMerchant >= MLN.DEBUG) {
							MLN.debug(this, "Found good move in "+townHall.getVillageQualifiedName()+" for merchant : "
									+ merchantRecord);
						}

					} else if (nbNightsMerchant > 3) {
						backupTargets.add(inn);

						if (MLN.LogMerchant >= MLN.DEBUG) {
							MLN.debug(this, "Found backup move in "+townHall.getVillageQualifiedName()+" for merchant : "
									+ merchantRecord);
						}
					}
				}
			}
		}

		if ((targets.size() == 0) && (backupTargets.size() == 0)) {
			if (MLN.LogMerchant >= MLN.MINOR) {
				MLN.minor(this, "Failed to find a destination for merchant: "
						+ merchantRecord);
			}
			return;
		}

		Building inn;

		if (targets.size() > 0) {
			inn = targets.get(MillCommonUtilities.randomInt(targets.size()));
		} else {
			inn = backupTargets.get(MillCommonUtilities.randomInt(backupTargets.size()));
		}

		if (inn.merchantRecord == null) {
			moveMerchant(inn);
		} else if ((inn.nbNightsMerchant > 1) || forced) {
			swapMerchants(inn);
		}
	}

	private void attemptPlanNewRaid() {

		for (final VillagerRecord vr : vrecords) {

			if (vr.raidingVillage)//can't raid if there are enemies still there
				return;

		}

		//100% margin (to attack villages with close ratings)
		final int raidingStrength=(int) ((float)getVillageRaidingStrength()*2);

		if (MLN.LogDiplomacy>=MLN.DEBUG) {
			MLN.debug(this, "Checking out for new raid, strength: "+raidingStrength);
		}

		if (raidingStrength>0) {
			final Vector<Building> targets=new Vector<Building>();

			if (villageType.lonebuilding) {
				for (final Building distantVillage : mw.allBuildings()) {
					if ((distantVillage != null) && distantVillage.isTownhall && (distantVillage.villageType!=null) && !distantVillage.villageType.lonebuilding &&
							(getPos().distanceTo(distantVillage.getPos())<MLN.BanditRaidRadius) &&
							(distantVillage.getVillageDefendingStrength()<raidingStrength)) {

						if (MLN.LogDiplomacy>=MLN.DEBUG) {
							MLN.debug(this, "Lone building valid target: "+distantVillage);
						}

						targets.add(distantVillage);
					}

				}
			} else {
				for (final Point p : relations.keySet()) {
					if (relations.get(p)<Building.RELATION_OPENCONFLICT) {
						final Building distantVillage=mw.getBuilding(p);


						if (distantVillage!=null) {

							if (MLN.LogDiplomacy>=MLN.DEBUG) {
								MLN.debug(this, "Testing village valid target: "+distantVillage+"/"+distantVillage.getVillageDefendingStrength());
							}

							if (distantVillage.getVillageDefendingStrength()<raidingStrength) {
								if (MLN.LogDiplomacy>=MLN.DEBUG) {
									MLN.debug(this, "Village valid target: "+distantVillage);
								}

								targets.add(distantVillage);
							}
						}
					}

				}
			}

			if (!targets.isEmpty()) {
				final Building target=targets.get(MillCommonUtilities.randomInt(targets.size()));

				//no raid between buildings that are both inactive
				if (isActive || target.isActive) {
					raidPlanningStart=worldObj.getWorldTime();
					raidStart=0;
					raidTarget=target.getPos();

					if (MLN.LogDiplomacy>=MLN.MAJOR) {
						MLN.major(this, "raidTarget set: "+raidTarget+" name: "+target.name);
					}

					saveNeeded=true;
					saveReason="Raid planned";


					ServerSender.sendTranslatedSentenceInRange(worldObj, getPos(),MLN.BackgroundRadius,MLN.DARKRED, "raid.planningstarted",getVillageQualifiedName(),target.getVillageQualifiedName());
				}
			}
		}
	}

	public PathingWorker calculatePath(MillVillager villager, Point start,
			Point dest, boolean extraLog) {

		if (!MillVillager.usingBinaryPathing && MillVillager.usingCustomPathing) {
			if (pathing == null) {
				try {
					rebuildPathing(true);
				} catch (final MillenaireException e) {
					MLN.printException("Error when rebuilding pathing:", e);
				}
			}

			if (pathing == null) {
				if ((MLN.LogPathing >= MLN.MAJOR) && extraLog) {
					MLN.major(this,
							"Can't do pathing as can't generate connections.");
				}
				return null;
			}

			if (!pathing.isInArea(start)) {
				if ((MLN.LogPathing >= MLN.MAJOR) && extraLog) {
					MLN.major(this, "Start outside of TH area.");
				}
				return null;

			}

			if (!pathing.isInArea(dest)) {
				if ((MLN.LogPathing >= MLN.MAJOR) && extraLog) {
					MLN.major(this, "Dest outside of TH area.");
				}
				return null;
			}

			if ((MLN.LogConnections >= MLN.MAJOR) && extraLog) {
				MLN.major(this,
						"calling getPath: " + (start.getiX() - winfo.mapStartX)
						+ "/" + (start.getiZ() - winfo.mapStartZ)
						+ " to " + (dest.getiX() - winfo.mapStartX)
						+ "/" + (dest.getiZ() - winfo.mapStartZ));
			}

			return pathing.createWorkerForPath(villager,start.getiX(), start.getiZ(), dest.getiX(), dest.getiZ());

		} else
			return null;
	}

	public void calculatePathsToClear() {
		if (pathsToBuild!=null) {

			final Vector<Vector<BuildingBlock>> pathsToBuildLocal=pathsToBuild;

			final long startTime=System.currentTimeMillis();

			final Vector<Point> oldPathPointsToClearNew=new Vector<Point>();

			final HashSet<Point> newPathPoints=new HashSet<Point>();

			for (final Vector<BuildingBlock> path : pathsToBuildLocal) {
				for (final BuildingBlock bp : path) {
					newPathPoints.add(bp.p);
				}
			}

			for (int x=winfo.mapStartX;x<(winfo.mapStartX+winfo.length);x++) {
				for (int z=winfo.mapStartZ;z<(winfo.mapStartZ+winfo.width);z++) {
					final int basey=winfo.topGround[x-winfo.mapStartX][z-winfo.mapStartZ];

					for (int dy=-2;dy<3;dy++) {
						final int y=dy+basey;

						final int bid=worldObj.getBlockId(x, y, z);
						final int meta=worldObj.getBlockMetadata(x, y, z);

						if ((bid==Mill.path.blockID || bid==Mill.pathSlab.blockID) && meta<8) {
							final Point p=new Point(x,y,z);
							if (!newPathPoints.contains(p)) {
								oldPathPointsToClearNew.add(p);
							}
						}
					}
				}
			}

			oldPathPointsToClearIndex=0;
			oldPathPointsToClear=oldPathPointsToClearNew;

			if (MLN.LogVillagePaths>=MLN.MINOR) {
				MLN.minor(this, "Finished looking for paths to clear. Found: "+oldPathPointsToClear.size()+". Duration: "+((System.currentTimeMillis()-startTime)+" ms."));
			}
		}
	}

	public void callForHelp(Entity attacker) {
		if (MLN.LogGeneralAI >= MLN.DEBUG) {
			MLN.debug(this, "Calling for help among: " + villagers.size()
					+ " villagers.");
		}
		for (final MillVillager villager : villagers) {
			if (MLN.LogGeneralAI >= MLN.DEBUG) {
				MLN.debug(
						villager,
						"Testing villager. Will fight? "
								+ villager.helpsInAttacks()
								+ ". Current target? "
								+ villager.getEntityToAttack()
								+ ". Distance to threat: "
								+ villager.getPos().distanceTo(attacker));
			}
			if ((villager.getEntityToAttack() == null) && villager.helpsInAttacks() && !villager.isRaider) {

				if (villager.getPos().distanceTo(attacker) < MillVillager.ATTACK_RANGE) {
					if (MLN.LogGeneralAI >= MLN.MAJOR) {
						MLN.major(villager,
								"Off to help a friend attacked by attacking: "
										+ attacker);
					}
					villager.setEntityToAttack(attacker);
					villager.clearGoal();
					villager.speakSentence("calltoarms", 0, 50, 1);
				}
			}
		}
	}

	public boolean canAffordBuild(BuildingPlan plan) {

		for (final InvItem key : plan.resCost.keySet()) {
			if (plan.resCost.get(key) > countGoods(key.id(), key.meta))
				return false;
		}
		return true;
	}

	public boolean canAffordBuildAfterGoal(BuildingPlan plan) {

		final BuildingPlan goalPlan = getCurrentGoalBuildingPlan();

		for (final InvItem key : plan.resCost.keySet()) {
			if ((goalPlan != null) && goalPlan.resCost.containsKey(key)) {
				if ((plan.resCost.get(key) + goalPlan.resCost.get(key)) > countGoods(
						key.id(), key.meta))
					return false;
			} else {
				if (plan.resCost.get(key) > countGoods(key.id(), key.meta))
					return false;
			}
		}
		return true;
	}

	public void cancelBuilding(BuildingLocation location) {


		if (location.isLocationSamePlace(buildingLocationIP)) {
			buildingLocationIP=null;
		}

		if (location.isLocationSamePlace(buildingGoalLocation)) {
			buildingGoalLocation=null;
			buildingGoal=null;
		}

		for (final Vector<BuildingProject> projects : buildingProjects) {
			for (final BuildingProject project : projects) {
				if (project.location == location) {
					projects.remove(project);
					break;
				}
			}
		}

		buildings.remove(location.pos);

		winfo.removeBuildingLocation(location);

		mw.removeBuilding(location.pos);

	}

	private void cancelRaid() {

		if (MLN.LogDiplomacy>=MLN.MAJOR) {
			MLN.major(this, "Cancelling raid");
		}

		raidPlanningStart=0;
		raidStart=0;
		raidTarget=null;
	}

	public boolean canChildMoveIn(int pGender, String familyName) {

		if ((pGender == MillVillager.FEMALE)
				&& (location.femaleResident.size() == 0))
			return false;

		if ((pGender == MillVillager.MALE) && (location.maleResident.size() == 0))
			return false;

		// anti-incest check:
		for (final VillagerRecord vr : vrecords) {
			if ((vr.gender != pGender) && !vr.getType().isChild
					&& vr.familyName.equals(familyName))
				return false;
		}

		int nbAdultSameSex = 0;
		for (final VillagerRecord vr : vrecords) {
			if ((vr.gender == pGender) && !(vr.getType()==null) && !vr.getType().isChild) {
				nbAdultSameSex++;
			}
		}

		if ((pGender == MillVillager.MALE)
				&& (nbAdultSameSex >= location.maleResident.size()))
			return false;

		if ((pGender == MillVillager.FEMALE)
				&& (nbAdultSameSex >= location.femaleResident.size()))
			return false;

		return true;
	}

	public boolean canSee(int x1, int z1, int x2, int z2) {

		if ((x1 < winfo.mapStartX) || (x1 >= (winfo.mapStartX + winfo.length))
				|| (z1 < winfo.mapStartZ)
				|| (z1 >= (winfo.mapStartZ + winfo.width))) {
			if (MLN.LogPathing >= MLN.DEBUG) {
				MLN.debug(this, "Start outside of TH area.");
			}
			return false;
		}

		if ((x2 < winfo.mapStartX) || (x2 >= (winfo.mapStartX + winfo.length))
				|| (z1 < winfo.mapStartZ)
				|| (z2 >= (winfo.mapStartZ + winfo.width))) {
			if (MLN.LogPathing >= MLN.DEBUG) {
				MLN.debug(this, "Dest outside of TH area.");
			}
			return false;
		}

		if (pathing == null)
			return false;
		x1 -= winfo.mapStartX;
		z1 -= winfo.mapStartZ;
		x2 -= winfo.mapStartX;
		z2 -= winfo.mapStartZ;
		return pathing.canSee(new Point2D(x1, z2), new Point2D(x2, z2));
	}

	public void checkBattleStatus() {
		int nbAttackers=0;
		int nbLiveAttackers=0;
		int nbLiveDefenders=0;
		Point attackingVillagePos=null;

		for (final VillagerRecord vr : vrecords) {
			if (vr.raidingVillage) {
				nbAttackers++;
				if (!vr.killed) {
					nbLiveAttackers++;
				}

				attackingVillagePos=vr.originalVillagePos;
			} else if (vr.getType().helpInAttacks && !vr.killed && !vr.awayraiding && !vr.awayhired) {
				nbLiveDefenders++;
			}
		}



		if (isTownhall) {
			if (chestLocked && (nbLiveDefenders==0)) {
				unlockAllChests();

				ServerSender.sendTranslatedSentenceInRange(worldObj, getPos(),MLN.BackgroundRadius,MLN.DARKRED,
						"ui.allchestsunlocked",getVillageQualifiedName());
			} else if (!chestLocked && (nbLiveDefenders>0)) {
				lockAllBuildingsChests();
			}
		}


		//MLN.major(this, "checkBattleStatus: "+nbLiveDefenders+"/"+nbLiveAttackers+"/"+nbAttackers);

		if (nbAttackers>0) {//raid in progress
			underAttack=true;
			if ((nbLiveAttackers==0) || (nbLiveDefenders==0)) {//one side won

				boolean finish=false;
				//extra condition if the attackers won: one at least must have reached the village's
				//defending area
				if (nbLiveAttackers>0) {
					for (final MillVillager v : villagers) {
						if (!finish && v.isRaider && (getDefendingPos().distanceToSquared(v)<25)) {
							finish=true;
						}
					}
				} else {
					finish=true;
				}

				if (finish) {
					if (attackingVillagePos==null) {
						MLN.error(this, "Wanted to end raid but can't find originating village's position.");
						clearAllAttackers();
					} else {
						final Building attackingVillage=mw.getBuilding(attackingVillagePos);

						if (attackingVillage==null) {//can happen, if the village was destroyed by the player
							clearAllAttackers();
						} else {
							final boolean endedProperly=attackingVillage.endRaid();

							//normally never happens, save for bug fixed in 2.4.6
							if (!endedProperly) {
								clearAllAttackers();
							}
						}
					}
				}
			}
		} else {
			underAttack=false;
		}
	}

	private void checkExploreTag(EntityPlayer player) {
		if ((player != null) && !mw.getProfile(player.username).isTagSet(location.getPlan().exploreTag)) {

			if ((pos.distanceToSquared(player) < 16)) {

				// Testing that there is a line of sight between the player and
				// the chest
				boolean valid = true;
				int x = pos.getiX();
				int z = pos.getiZ();

				while (valid
						&& ((x != (int) player.posX) || (z != (int) player.posZ))) {
					final int bid = worldObj.getBlockId(x, pos.getiY() + 1, z);

					if ((bid > 0) && Block.blocksList[bid].isOpaqueCube()) {
						valid = false;
					} else {
						if (x > (int) player.posX) {
							x--;
						} else if (x < (int) player.posX) {
							x++;
						} else {
							if (z > (int) player.posZ) {
								z--;
							} else if (z < (int) player.posZ) {
								z++;
							}
						}
					}
				}

				if (valid) {
					mw.getProfile(player.username)
					.setTag(location.getPlan().exploreTag);

					ServerSender.sendTranslatedSentence(player,MLN.DARKGREEN, "other.exploredbuilding",location.getPlan().nativeName);
				}
			}
		}
	}

	public void checkSeller() {

		if (!worldObj.isDaytime() || underAttack)
			return;

		if ((closestPlayer == null) || controlledBy(closestPlayer.username))
			return;

		if ((closestPlayer != null) && (seller == null) && (getReputation(closestPlayer.username) >= MIN_REPUTATION_FOR_TRADE) && chestLocked) {
			sellingPlace = null;

			for (final BuildingLocation l : getLocations()) {
				if ((l.level >= 0) && (l.chestPos != null) && (l.shop != null)
						&& (l.shop.length() > 0)) {
					if ((l.getSellingPos() != null)
							&& (l.getSellingPos().distanceTo(closestPlayer) < 3)) {
						sellingPlace = l.getSellingPos();
					} else if ((l.getSellingPos() == null)
							&& (l.sleepingPos.distanceTo(closestPlayer) < 3)) {
						sellingPlace = l.sleepingPos;
					}
				}
			}

			if (sellingPlace != null) {
				for (final MillVillager villager : villagers) {
					if (villager.isSeller()) {
						if ((builder != villager)
								&& ((seller == null) || (sellingPlace
										.distanceToSquared(villager) < sellingPlace
										.distanceToSquared(seller)))) {
							seller = villager;
						}
					}
				}

				if (seller != null) {
					seller.clearGoal();
					seller.goalKey = Goal.beSeller.key;
					Goal.beSeller.onAccept(seller);
					if (MLN.LogSelling >= MLN.DEBUG) {
						MLN.debug(this, "Sending seller: " + seller);
					}
				}
			}
		}
	}

	public void checkWorkers() {

		if ((seller != null)
				&& (!Goal.beSeller.key.equals(seller.goalKey) || seller.isDead)) {
			seller = null;
		}

		if ((builder != null)
				&& (builder.isDead || (!Goal.getResourcesForBuild.key
						.equals(builder.goalKey) && !Goal.construction.key
						.equals(builder.goalKey)))) {
			if (MLN.LogBuildingPlan >= MLN.MAJOR) {
				MLN.major(this, builder.getName() + " is no longer building.");
			}
			builder = null;
		}
	}

	private void clearAllAttackers() {
		int nbCleared=0;

		for (int i=vrecords.size()-1;i>=0;i--) {
			final VillagerRecord vr=vrecords.get(i);
			if (vr.raidingVillage) {
				vrecords.remove(i);

				if (vr.getHouse().getTownHall()!=this) {
					if (MLN.LogDiplomacy>=MLN.MAJOR) {
						MLN.error(this, "Tried clearing attacker record but its house is set to "+vr.getHouse()+" from village "+vr.getHouse().getTownHall()+". TH is: "+vr.getTownHall());
					}
				} else {
					vr.getHouse().removeVillagerRecord(vr.id);
				}


				nbCleared++;
			}
		}

		if (MLN.LogDiplomacy>=MLN.MAJOR) {
			MLN.major(this, "Cleared "+nbCleared+" attackers.");
		}

		for (int i=villagers.size()-1;i>=0;i--) {
			final MillVillager v = villagers.get(i);
			if (v.isRaider) {
				villagers.remove(i);
				v.getHouse().villagers.remove(v);
				v.despawnVillagerSilent();
				if (MLN.LogDiplomacy>=MLN.MAJOR) {
					MLN.major(this, "Despawning invader: "+v);
				}
			}
		}
	}

	public void clearOldPaths() {
		if (oldPathPointsToClear!=null) {
			for (final Point p : oldPathPointsToClear) {
				final int bid=p.getId(worldObj);
				final int bidBelow=p.getBelow().getId(worldObj);

				if (bid==Mill.pathSlab.blockID) {
					p.setBlock(worldObj, 0, 0, true, false);
				} else if (bid==Mill.path.blockID) {
					if (MillCommonUtilities.getBlockIdValidGround(bidBelow,true)>0) {
						p.setBlock(worldObj, MillCommonUtilities.getBlockIdValidGround(bidBelow,true), 0, true, false);
					} else {
						p.setBlock(worldObj, Block.dirt.blockID, 0, true, false);
					}
				}
			}
			oldPathPointsToClear=null;
			pathsChanged=true;
			requestSave("paths clearing rushed");
		}
	}

	private void completeConstruction() throws MillenaireException {
		if ((buildingLocationIP != null) && (getBblocks() == null)) {

			final BuildingPlan plan=this.getCurrentBuildingPlan();

			registerBuildingLocation(buildingLocationIP);
			updateWorldInfo();

			if (buildingLocationIP.isSameLocation(buildingGoalLocation)) {
				buildingGoalLocation = null;
				buildingGoal = null;
				buildingGoalIssue = null;
				buildingGoalLevel = -1;
			}

			builder = null;
			buildingLocationIP = null;

			if (plan.rebuildPath) {
				recalculatePaths(false);
			}
		}
	}

	public void constructCalculatedPaths() {

		if (pathsToBuild!=null) {

			if (MLN.LogVillagePaths>=MLN.MINOR) {
				MLN.minor(this, "Rebuilding calculated paths.");
			}

			for (final Vector<BuildingBlock> path : pathsToBuild) {
				if (!path.isEmpty()) {
					for (final BuildingBlock bp : path) {
						bp.pathBuild(this);
					}
				}
			}
			pathsToBuild=null;
			pathsChanged=true;
			requestSave("paths rushed");
		}
	}


	public boolean controlledBy(String playerName) {
		if (!this.isTownhall && (getTownHall()!=null))
			return getTownHall().controlledBy(playerName);

		return ((controlledBy!=null) && controlledBy.equals(mw.getProfile(playerName).key));
	}


	public int countChildren() {
		int nb = 0;
		for (final VillagerRecord vr : vrecords) {
			if ((vr.getType()!=null) && vr.getType().isChild) {
				nb++;
			}
		}
		return nb;
	}

	public int countGoods(int item) {
		return countGoods(item, 0);
	}

	public int countGoods(int item, int meta) {
		int count = 0;

		for (final Point p : chests) {
			final TileEntityChest chest = p.getMillChest(worldObj);
			count += MillCommonUtilities.countChestItems(chest, item, meta);
		}

		for (final Point p : furnaces) {
			final TileEntityFurnace furnace = p.getFurnace(worldObj);
			count += MillCommonUtilities.countFurnaceItems(furnace, item, meta);
		}

		return count;
	}

	public int countGoods(InvItem iv) {
		return countGoods(iv.id(), iv.meta);
	}

	public MillVillager createChild(MillVillager mother, Building townHall,
			String fathersName) {
		try {
			if (MLN.LogWorldGeneration >= MLN.MINOR) {
				MLN.minor(this, "Creating child: " + mother.familyName);
			}

			final int gender = getNewGender();
			final String type = (gender == MillVillager.MALE) ? mother.getMaleChild() : mother.getFemaleChild();

			final MillVillager child = MillVillager.createVillager(
					townHall.culture, type, gender, worldObj, getSleepingPos(),
					getPos(), townHallPos,false, null, mother.familyName);

			if (child == null)
				throw new MillenaireException(
						"Child not instancied in createVillager");

			addOrReplaceVillager(child);
			townHall.addOrReplaceVillager(child);
			final VillagerRecord vr = new VillagerRecord(mw,child);
			vr.fathersName = fathersName;
			vr.mothersName = mother.getName();
			addOrReplaceRecord(vr);
			townHall.addOrReplaceRecord(vr);
			worldObj.spawnEntityInWorld(child);

			return child;
		} catch (final Exception e) {
			Mill.proxy.sendChatAdmin("Error in createChild(). Check millenaire.log.");
			MLN.error(this, "Exception in createChild.onUpdate(): ");
			MLN.printException(e);
		}

		return null;
	}

	public String createResidents() throws MillenaireException {

		if ((location.maleResident.size() + location.femaleResident.size()) == 0)
			return null;

		String familyName = null;

		// first we create the first male and the first female
		// as a couple
		String husbandType = null;
		if (location.maleResident.size() > 0) {
			husbandType = location.maleResident.get(0);
		}
		String wifeType = null;
		if (location.femaleResident.size() > 0) {
			wifeType = location.femaleResident.get(0);
		}

		if (MLN.LogMerchant >= MLN.MINOR) {
			MLN.minor(this, "Creating " + husbandType + " and " + wifeType
					+ ": " + familyName);
		}

		VillagerRecord husbandRecord = null, wifeRecord = null;

		if (sleepingPos==null) {
			MLN.error(this, "Wanted to create villagers but sleepingPos is null!");
			return "";
		}

		if (husbandType != null) {
			final MillVillager husband = MillVillager.createVillager(culture,
					husbandType, MillVillager.MALE, worldObj, sleepingPos,
					getPos(), townHallPos,false, null, null);
			familyName = husband.familyName;
			addOrReplaceVillager(husband);
			husbandRecord = new VillagerRecord(mw,husband);
			addOrReplaceRecord(husbandRecord);
			worldObj.spawnEntityInWorld(husband);
		}

		if (wifeType != null) {

			final MillVillager wife = MillVillager.createVillager(culture,
					wifeType, MillVillager.FEMALE, worldObj, sleepingPos,
					getPos(), townHallPos,false, null, familyName);

			addOrReplaceVillager(wife);
			wifeRecord = new VillagerRecord(mw,wife);
			addOrReplaceRecord(wifeRecord);
			worldObj.spawnEntityInWorld(wife);
		}
		if (MLN.LogWorldGeneration >= MLN.MAJOR) {
			MLN.major(this, "Records: " + wifeRecord + "/" + husbandRecord);
		}

		if ((wifeRecord != null) && (husbandRecord != null)) {
			wifeRecord.spousesName = husbandRecord.getName();
			husbandRecord.spousesName = wifeRecord.getName();
		}

		// any other male or female are create independently:
		for (int i = 1; i < location.maleResident.size(); i++) {
			final MillVillager extraMale = MillVillager.createVillager(culture,
					location.maleResident.get(i), MillVillager.MALE, worldObj,
					sleepingPos, getPos(), townHallPos,false, null, null);
			addOrReplaceVillager(extraMale);
			final VillagerRecord vr = new VillagerRecord(mw,extraMale);
			addOrReplaceRecord(vr);
			worldObj.spawnEntityInWorld(extraMale);
		}
		for (int i = 1; i < location.femaleResident.size(); i++) {
			final MillVillager extraFemale = MillVillager.createVillager(culture,
					location.femaleResident.get(i), MillVillager.FEMALE,
					worldObj, sleepingPos, getPos(), townHallPos,false, null, null);
			addOrReplaceVillager(extraFemale);
			final VillagerRecord vr = new VillagerRecord(mw,extraFemale);
			addOrReplaceRecord(vr);
			worldObj.spawnEntityInWorld(extraFemale);
		}

		if (isInn) {
			merchantCreated();
		} else {
			updateHouseSign();
		}

		return familyName;
	}

	public void deleteVillager(MillVillager villager) {
		while (villagers.remove(villager)) {
			;
		}
	}

	public void deleteVillagerFromRecords(MillVillager villager) {

		for (int i = vrecords.size() - 1; i >= 0; i--) {
			final VillagerRecord vr = vrecords.get(i);
			if (vr.matches(villager)) {
				vrecords.remove(i);
			}
		}

		saveNeeded = true;
		saveReason = "Deleted villager";
	}

	public void destroyVillage() {

		if (MLN.LogVillage>=MLN.MAJOR) {
			MLN.major(this, "Destroying the village!");
		}

		//releasing chests:
		for (final Point p : chests) {
			final TileEntityMillChest chest=p.getMillChest(worldObj);
			if (chest!=null) {
				chest.buildingPos=null;
			}
		}
		for (final Point p : buildings) {
			final Building building=mw.getBuilding(p);
			if (building!=null) {
				for (final Point p2 : chests) {
					final TileEntityMillChest chest=p2.getMillChest(worldObj);
					if (chest!=null) {
						chest.buildingPos=null;
					}
				}
			}
		}


		//first, remaining villagers
		for (final MillVillager villager : villagers) {
			villager.despawnVillager();
		}

		//then, buildings
		for (final Point p : buildings) {
			mw.removeBuilding(p);
		}

		//then, village or lone building
		mw.removeVillageOrLoneBuilding(getPos());


		//finally, removing the village's saved file
		final File millenaireDir = mw.millenaireDir;

		if (!millenaireDir.exists()) {
			millenaireDir.mkdir();
		}
		final File buildingsDir = new File(millenaireDir, "buildings");

		if (!buildingsDir.exists()) {
			buildingsDir.mkdir();
		}
		final File file1 = new File(buildingsDir, getPos().getPathString() + ".gz");

		if (file1.exists()) {
			file1.delete();
		}
	}

	public void displayInfos(EntityPlayer player) {

		if (location == null)
			return;

		int nbAdults = 0, nbGrownChild = 0;

		for (final MillVillager villager : villagers) {
			if (!villager.isChild()) {
				nbAdults++;
			} else {
				if (villager.size == MillVillager.max_child_size) {
					nbGrownChild++;
				}
			}
		}

		ServerSender.sendChat(player,MLN.LIGHTGREEN,"It has " + villagers.size() + " villagers registered. ("
				+ nbAdults + " adults, " + nbGrownChild + " grown children)");
		ServerSender.sendChat(player,MLN.LIGHTGREEN,"Pos: " + getPos() + " sell pos:" + getSellingPos());

		if (isTownhall) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"It has " + buildings.size() + " houses registered.");
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Connections build: " + (pathing != null));
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Village name: " + getVillageQualifiedName());
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Current building plan: " + getCurrentBuildingPlan() + " at "
					+ buildingLocationIP);
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Current builder: " + builder);
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Current seller: " + seller);
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Rep: " + getReputation(player.username) + " bought: " + buildingsBought);
		}

		if (isInn) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Merchant: " + merchantRecord);
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Merchant nights: " + nbNightsMerchant);
		}

		if (location.tags == null) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"UNKNOWN TAGS");
		} else {
			if (location.tags.size() > 0) {
				String s = "Tags: ";
				for (final String tag : location.tags) {
					s += tag + " ";
				}
				ServerSender.sendChat(player,MLN.LIGHTGREEN,s);
			}
		}
		if (chests.size() > 1) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Chests registered: " + chests.size());
		}
		if (furnaces.size() > 1) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Furnaces registered: " + furnaces.size());
		}


		for (int i=0;i<soilTypes.size();i++) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Fields registered: " + soilTypes.get(i)+": "+soils.get(i).size());
		}

		if (sugarcanesoils.size() > 0) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Sugar cane soils registered: " + sugarcanesoils.size());
		}

		if (fishingspots.size() > 0) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Fishing spots registered: " + fishingspots.size());
		}
		if (stalls.size() > 0) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Stalls registered: " + stalls.size());
		}
		if (woodspawn.size() > 0) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,"Wood spawn registered: " + woodspawn.size());
		}
		if (spawns.size() > 0) {
			String s = "Pens: ";
			for (int i = 0; i < spawns.size(); i++) {
				s += spawnTypes.get(i) + ": " + spawns.get(i).size() + " ";
			}
			ServerSender.sendChat(player,MLN.LIGHTGREEN,s);
		}

		if (mobSpawners.size() > 0) {
			String s = "Mob spawners: ";
			for (int i = 0; i < mobSpawners.size(); i++) {
				s += mobSpawnerTypes.get(i) + ": " + mobSpawners.get(i).size()
						+ " ";
			}
			ServerSender.sendChat(player,MLN.LIGHTGREEN,s);
		}

		if (sources.size() > 0) {
			String s = "Sources: ";
			for (int i = 0; i < sources.size(); i++) {
				s += Item.itemsList[sourceTypes.get(i)].getUnlocalizedName() + ": "
						+ sources.get(i).size() + " ";
			}
			ServerSender.sendChat(player,MLN.LIGHTGREEN,s);
		}

		for (final MillVillager villager : villagers) {
			if (villager == null) {
				ServerSender.sendChat(player,MLN.LIGHTGREEN,"NULL villager!");
			} else {
				ServerSender.sendChat(player,MLN.LIGHTGREEN,villager.getClass().getSimpleName() + ": "
						+ villager.getPos()
						+ (villager.isEntityAlive() ? "" : " DEAD") + " "
						+ villager.getGoalLabel(villager.goalKey));
			}
		}

		String s = "LKey: " + location.key + " Shop: " + location.shop
				+ " special: ";
		if (isTownhall) {
			s += "Town Hall ";
		}
		if (isInn) {
			s += "Inn ";
		}
		if (isMarket) {
			s += "Market";
		}

		if (pujas!=null) {
			s+="Shrine ";
		}

		if (!s.equals("")) {
			ServerSender.sendChat(player,MLN.LIGHTGREEN,s);
		}

		if ((pathsToBuild!=null) || (oldPathPointsToClear!=null)) {

			if (pathsToBuild!=null) {
				s="pathsToBuild: "+pathsToBuild.size()+" "+pathsToBuildIndex+"/"+pathsToBuildPathIndex;
			} else {
				s="pathsToBuild:null";
			}

			if (oldPathPointsToClear!=null) {
				s=s+" oldPathPointsToClear: "+oldPathPointsToClear.size()+" "+oldPathPointsToClearIndex;
			} else {
				s=s+" oldPathPointsToClear:null";
			}

			ServerSender.sendChat(player,MLN.LIGHTGREEN,s);
		}

		validateVillagerList();
	}

	//return false if the raid couldn't be ended cleanly
	private boolean endRaid() {

		final Building targetVillage=mw.getBuilding(raidTarget);

		if (targetVillage==null) {
			MLN.error(this, "endRaid() called but couldn't find raidTarget at: "+raidTarget);
			return false;
		} else {
			if (MLN.LogDiplomacy>=MLN.MAJOR) {
				MLN.major(this, "Called to end raid on "+targetVillage);
			}
		}

		final float defendingForce=targetVillage.getVillageDefendingStrength()*(1+MillCommonUtilities.random.nextFloat());
		final float attackingForce=targetVillage.getVillageAttackerStrength()*(1+MillCommonUtilities.random.nextFloat());

		boolean attackersWon;

		if (attackingForce==0) {
			attackersWon=false;
		} else if (defendingForce==0) {
			attackersWon=true;
		} else {
			final float ratio=attackingForce/defendingForce;
			attackersWon=(ratio>1.2);
		}

		if (MLN.LogDiplomacy>=MLN.MAJOR) {
			MLN.major(this, "Result of raid: "+attackersWon+" ("+attackingForce+"/"+attackingForce+")");
		}

		//re-activating local records
		for (final VillagerRecord vr : vrecords) {
			if (vr.awayraiding) {
				vr.awayraiding=false;
				final VillagerRecord awayRecord=targetVillage.getVillagerRecordById(vr.id);
				if (awayRecord!=null) {
					vr.killed=awayRecord.killed;
				} else {
					vr.killed=false;
				}
			}
		}

		//removing attackers from distant village
		targetVillage.clearAllAttackers();


		//removing targets from defending villagers
		for (final MillVillager v : targetVillage.villagers) {
			if ((v.getEntityToAttack() != null) && (v.getEntityToAttack() instanceof MillVillager)) {
				v.setEntityToAttack(null);
			}
		}

		cancelRaid();

		targetVillage.underAttack=false;

		if (attackersWon) {
			int nbStolen=0;
			String taken ="";

			for (final Goods good : culture.goodsVector) {
				if (nbStolen<=1024) {
					int nbToTake=nbGoodNeeded(good.item.id(),good.item.meta);
					nbToTake=Math.min(nbToTake,Math.max(0, 1024-nbStolen));


					if (nbToTake>0) {
						nbToTake=Math.min(nbToTake, targetVillage.countGoods(good.item));

						if (nbToTake>0) {

							if (MLN.LogDiplomacy>=MLN.DEBUG) {
								MLN.debug(this, "Able to take: "+nbToTake+" "+Mill.proxy.getItemName(good.item.id(), good.item.meta));
							}

							targetVillage.takeGoods(good.item, nbToTake);
							this.storeGoods(good.item, nbToTake);
							nbStolen+=nbToTake;

							taken+=";"+good.item.id()+"/"+good.item.meta+"/"+nbToTake;
						}
					}
				}
			}

			raidsPerformed.add("success;"+targetVillage.getVillageQualifiedName()+taken);
			targetVillage.raidsSuffered.add("success;"+getVillageQualifiedName()+taken);

			if (MLN.LogDiplomacy>=MLN.MAJOR) {
				MLN.major(this, "Raid on "+targetVillage+" successfull ("+attackingForce+"/"+defendingForce+")");
			}

			ServerSender.sendTranslatedSentenceInRange(worldObj, getPos(), MLN.BackgroundRadius,MLN.DARKRED,"raid.raidsuccesfull",getVillageQualifiedName(),targetVillage.getVillageQualifiedName(),""+nbStolen);
		} else {
			raidsPerformed.add("failure;"+targetVillage.getVillageQualifiedName());
			targetVillage.raidsSuffered.add("failure;"+getVillageQualifiedName());

			if (MLN.LogDiplomacy>=MLN.MAJOR) {
				MLN.major(this, "Raid on "+targetVillage+" failed ("+attackingForce+"/"+defendingForce+")");
			}

			ServerSender.sendTranslatedSentenceInRange(worldObj, getPos(), MLN.BackgroundRadius,MLN.DARKRED,"raid.raidfailed",getVillageQualifiedName(),targetVillage.getVillageQualifiedName());
		}

		//temp
		MLN.major(this, "Finished ending raid. Records: "+vrecords.size());

		targetVillage.saveTownHall("Raid on village ended");
		saveNeeded=true;
		saveReason="Raid finished";

		return true;
	}

	private void fillinBuildingLocation(BuildingLocation location) {

		mw.testLocations("fillinBuildingLocation start");

		boolean registered = false;
		for (final Vector<BuildingProject> projectsLevel : buildingProjects) {

			final Vector<BuildingProject> temp = new Vector<BuildingProject>(
					projectsLevel);// needed to avoid concurrent modifications
			for (final BuildingProject project : temp) {
				int pos = 0;

				if (!registered && (project.location == null)
						&& location.key.equals(project.key)) {
					project.location = location;
					registered = true;
					if (MLN.LogBuildingPlan >= MLN.MINOR) {
						MLN.minor(this, "Registered building: " + location
								+ " (level " + location.level + ", variation: "
								+ location.getVariation() + ")");
					}

					if (project.location.level >= 0) {
						for (final String s : project.location.subBuildings) {
							final BuildingProject newproject = new BuildingProject(
									culture.getBuildingPlanSet(s));
							newproject.location = location
									.createLocationForSubBuilding(s);
							projectsLevel.insertElementAt(newproject, pos + 1);
							if (MLN.LogBuildingPlan >= MLN.MAJOR) {
								MLN.major(this,
										"Adding sub-building to project list: "
												+ newproject + " at pos " + pos
												+ " in " + projectsLevel);
							}
						}
					}
					pos++;
				} else if (!registered && (project.location != null)
						&& (project.location.level < 0)
						&& location.key.equals(project.key)) {
					project.location = location;
					registered = true;
					if (MLN.LogBuildingPlan >= MLN.MAJOR) {
						MLN.major(this, "Registered subbuilding: " + location
								+ " (level " + location.level + ", variation: "
								+ location.getVariation() + ")");
					}
				}
			}
		}

		if (!registered) {
			final BuildingProject project = new BuildingProject(
					culture.getBuildingPlanSet(location.key));
			project.location = location;
			if (villageType.playerControlled) {
				buildingProjects.get(3).add(project);// core projects
			} else {
				buildingProjects.lastElement().add(project);// extra
			}
		}

		mw.testLocations("fillinBuildingLocation end");
	}

	public void fillStartingGoods() {
		for (final Point p : chests) {
			final TileEntityMillChest chest=p.getMillChest(worldObj);
			if (chest!=null) {
				for (int i=0;i<chest.getSizeInventory();i++) {
					chest.setInventorySlotContents(i, null);
				}
			}
		}

		for (final StartingGood sg : location.getPlan().startingGoods) {
			if (MillCommonUtilities.probability(sg.probability)) {
				int nb=sg.fixedNumber;
				if (sg.randomNumber>0) {
					nb+=MillCommonUtilities.randomInt(sg.randomNumber+1);
				}
				if (nb>0) {
					final int chestId=MillCommonUtilities.randomInt(chests.size());

					final TileEntityMillChest chest=chests.get(chestId).getMillChest(worldObj);
					if (chest!=null) {
						MillCommonUtilities.putItemsInChest(chest, sg.item.id(), sg.item.meta, nb);
					}

				}
			}
		}

		if (MLN.DEV) {
			testModeGoods();
		}
	}

	private Point findAttackerSpawnPoint(Point origin) {

		int x,z;

		//We start from the middle of the side of the village's "rectangle"
		//closest to the attacking village
		if (origin.getiX()>pos.getiX()) {
			x=Math.min(winfo.length-5,(winfo.length/2)+50);
		} else {
			x=Math.max(5,(winfo.length/2)-50);
		}

		if (origin.getiZ()>pos.getiZ()) {
			z=Math.min(winfo.width-5,(winfo.width/2)+50);
		} else {
			z=Math.max(5,(winfo.width/2)-50);
		}

		///40 attempts, extending the range each time
		for (int i=0;i<40;i++) {
			int tx=(x+MillCommonUtilities.randomInt(5+i))-MillCommonUtilities.randomInt(5+i);
			int tz=(z+MillCommonUtilities.randomInt(5+i))-MillCommonUtilities.randomInt(5+i);

			tx=Math.max(Math.min(tx, winfo.length-1),0);
			tz=Math.max(Math.min(tz, winfo.width-1),0);

			tx=Math.min(tx,(winfo.length/2)+50);
			tx=Math.max(tx,(winfo.length/2)-50);

			tz=Math.min(tz,(winfo.width/2)+50);
			tz=Math.max(tz,(winfo.width/2)-50);

			if (winfo.canBuild[tx][tz]) {
				final Chunk chunk=worldObj.getChunkFromBlockCoords(winfo.mapStartX+tx, winfo.mapStartZ+tz);
				if (chunk.isChunkLoaded)
					return new Point(winfo.mapStartX+tx,
							MillCommonUtilities.findTopSoilBlock(worldObj, winfo.mapStartX+tx, winfo.mapStartZ+tz)+1,
							winfo.mapStartZ+tz);
			}
		}

		//failure mode: return defending pos
		return getDefendingPos();
	}

	private void findBuildingConstruction() {

		if ((buildingGoal == null) || (buildingLocationIP != null))
			return;

		BuildingProject goalProject = null;

		for (final Vector<BuildingProject> projectsLevel : buildingProjects) {
			for (final BuildingProject project : projectsLevel) {
				if ((buildingGoalLocation != null)
						&& buildingGoalLocation
						.isSameLocation(project.location)) {
					goalProject = project;
				} else if ((buildingGoalLocation == null)
						&& (project.location == null)
						&& buildingGoal.equals(project.key)) {
					goalProject = project;
				}

			}
		}

		if (MLN.LogBuildingPlan >= MLN.DEBUG) {
			MLN.debug(this, "Building goal project: " + goalProject + " ");
		}

		if (goalProject == null) {
			MLN.error(this, "Could not find building project for "
					+ buildingGoal + " and " + buildingGoalLocation
					+ ", cancelling goal.");
			buildingGoal = null;
			return;
		}

		if ((goalProject.location != null) && (goalProject.location.level >= 0)
				&& goalProject.location.upgradesAllowed) {// upgrades to
			// existing building
			if (canAffordBuild(goalProject.getPlan(buildingGoalVariation,
					buildingGoalLevel))) {
				if (buildingGoalLocation != null) {
					buildingLocationIP = buildingGoalLocation;
				} else {
					buildingLocationIP = goalProject.location;
				}

				setBblocks(goalProject.getPlan(buildingGoalVariation,
						buildingGoalLevel).getBuildingPoints(
								worldObj, buildingLocationIP, false));

				if (MLN.LogBuildingPlan >= MLN.MAJOR) {
					MLN.major(this, "Upgrade project possible at: " + location
							+ " for level " + buildingGoalLevel);
				}

				if (getBblocks().length == 0) {
					MLN.error(this, "No bblocks for  " + buildingLocationIP);
					try {
						rushBuilding();
					} catch (Exception e) {
						MLN.printException("Exception when trying to rush building:", e);
					}
				}

			} else {
				buildingGoalIssue = "ui.lackingresources";
			}
		} else if ((goalProject.location != null)
				&& (goalProject.location.level < 0)) {// new sub-building

			if (canAffordBuild(goalProject.getPlan(buildingGoalVariation,
					buildingGoalLevel))) {
				if (buildingGoalLocation != null) {
					buildingLocationIP = buildingGoalLocation;
				} else {
					buildingLocationIP = goalProject.location;
				}


				setBblocks(goalProject.getPlan(buildingGoalVariation,
						buildingGoalLevel).getBuildingPoints(worldObj,
								buildingLocationIP, false));

				if (getBblocks().length == 0) {
					MLN.error(this, "No bblocks for  " + buildingLocationIP);
				}

			} else {
				buildingGoalIssue = "ui.lackingresources";
			}

		} else if (goalProject.location == null) {
			final boolean canAffordProject = canAffordBuild(goalProject
					.getPlan(buildingGoalVariation, 0));
			if (((System.currentTimeMillis() - lastFailedProjectLocationSearch) > LOCATION_SEARCH_DELAY)
					&& canAffordProject) {
				// BuildingLocation location =
				// goalProject.getPlan(buildingGoalVariation,
				// 0).testBuild(worldObj, xCoord, yCoord, zCoord,
				// MLN.VillageRadius, getLocations(), MLHelper.getRandom(),-1);
				final BuildingLocation location = goalProject.getPlan(
						buildingGoalVariation, 0).findBuildingLocation(winfo,pathing,
								this.location.pos, villageType.radius, MillCommonUtilities.getRandom(), -1);

				lastFailedProjectLocationSearch = System.currentTimeMillis();
				if (location != null) {
					lastFailedProjectLocationSearch = 0;
					buildingLocationIP = location;
					buildingGoalLocation = location;
					setBblocks(goalProject.getPlan(buildingGoalVariation, 0).getBuildingPoints(worldObj,buildingLocationIP, false));

					if (MLN.LogBuildingPlan >= MLN.MAJOR) {
						MLN.major(
								this,
								"New project location: Loaded "
										+ getBblocks().length
										+ " building blocks for "
										+ goalProject.getPlan(
												buildingGoalVariation, 0).planName);
					}

					final int groundLevel = MillCommonUtilities.findTopSoilBlock(worldObj,
							location.pos.getiX(), location.pos.getiZ());

					for (int i = groundLevel + 1; i < location.pos.getiY(); i++) {
						MillCommonUtilities.setBlockAndMetadata(worldObj, location.pos, Block.dirt.blockID, 0);
					}
					/**
					worldObj.setBlockWithNotify(location.pos.getiX(),
							location.pos.getiY(), location.pos.getiZ(),
							Block.signPost.blockID);
					final TileEntitySign sign = location.pos.getSign(worldObj);
					if (sign != null) {
						sign.signText =  MillCommonUtilities.limitSignText(new String[] {
								MLN.string("ui.inconstruction"),
								"",
								goalProject.getPlan(buildingGoalVariation, 0).nativeName,
						"" });
					}**/
					if (MLN.LogBuildingPlan >= MLN.MAJOR) {
						MLN.major(this, "Found location for building project: "
								+ location);
					}
				} else {
					buildingGoalIssue = "ui.nospace";
					lastFailedProjectLocationSearch = System
							.currentTimeMillis();
					if (MLN.LogBuildingPlan >= MLN.MAJOR) {
						MLN.major(this,
								"Searching for a location for the new project failed.");
					}
				}
			} else if (!canAffordProject) {
				buildingGoalIssue = "ui.lackingresources";
				if (MLN.LogBuildingPlan >= MLN.DEBUG) {
					MLN.debug(this, "Cannot afford building project.");
				}
			} else {
				buildingGoalIssue = "ui.nospace";
			}
		}

		if (buildingLocationIP != null)
			return;

		boolean attemptedConstruction = false;

		for (final Vector<BuildingProject> projectsLevel : buildingProjects) {
			for (final BuildingProject project : projectsLevel) {
				if ((goalProject == null) || (project != goalProject)) {
					if ((project.location == null)
							|| (project.location.level < 0)) {
						final BuildingPlan plan = project.planSet
								.getRandomStartingPlan();
						if (isValidProject(project)) {

							BuildingLocation location = null;
							if ((project.location == null)
									&& ((System.currentTimeMillis() - lastFailedOtherLocationSearch) > LOCATION_SEARCH_DELAY)
									&& canAffordBuildAfterGoal(plan)) {
								location = plan.findBuildingLocation(winfo,pathing,
										this.location.pos, villageType.radius,
										MillCommonUtilities.getRandom(), -1);
							} else if (project.location != null) {
								location = project.location
										.createLocationForLevel(0);
							}

							if (location != null) {
								lastFailedOtherLocationSearch = 0;

								buildingLocationIP = location;

								setBblocks(plan.getBuildingPoints(worldObj,
										buildingLocationIP, false));

								if (MLN.LogBuildingPlan >= MLN.MAJOR) {
									MLN.major(this,
											"New location non-project: Loaded "
													+ getBblocks().length
													+ " building blocks for "
													+ plan.planName);
								}

								/**
								worldObj.setBlockWithNotify(
										location.pos.getiX(),
										location.pos.getiY(),
										location.pos.getiZ(),
										Block.signPost.blockID);

								final TileEntitySign sign = (TileEntitySign) worldObj
								.getBlockTileEntity(
										location.pos.getiX(),
										location.pos.getiY(),
										location.pos.getiZ());

								if (sign != null) {
									sign.signText =  MillCommonUtilities.limitSignText(new String[] {
											MLN.string("ui.inconstruction"),
											"", plan.nativeName,
									"" });
								}**/
							} else {
								attemptedConstruction = true;
							}
						}
					} else {
						final int level = project.location.level + 1;
						final int variation = project.location.getVariation();

						if ((level < project.getLevelsNumber(variation))
								&& project.location.upgradesAllowed
								&& canAffordBuildAfterGoal(project.getPlan(
										variation, level))) {
							buildingLocationIP = project.location
									.createLocationForLevel(level);
							setBblocks(project.getPlan(variation, level)
									.getBuildingPoints(worldObj,buildingLocationIP,
											false));
							if (MLN.LogBuildingPlan >= MLN.MAJOR) {
								MLN.major(
										this,
										"Upgrade non-project: Loaded "
												+ getBblocks().length
												+ " building blocks for "
												+ project.getPlan(variation,
														level).planName
														+ " upgrade. Old level: "
														+ project.location.level
														+ " New level: " + level);
							}

						}
					}
				}
				if (buildingLocationIP != null) {
					break;
				}
			}
			if (buildingLocationIP != null) {
				break;
			}
		}
		if (attemptedConstruction) {
			lastFailedOtherLocationSearch = System.currentTimeMillis();
		}
	}

	private void findBuildingProject() {

		if ((buildingGoal != null) && (buildingGoal.length() > 0))
			return;

		if (noProjectsLeft)
			return;

		buildingGoal = null;
		buildingGoalLocation = null;

		if (MLN.LogBuildingPlan >= MLN.MINOR) {
			MLN.minor(this, "Searching for new building goal");
		}

		final Vector<BuildingProject> possibleProjects = new Vector<BuildingProject>();

		boolean foundNewBuildingsLevel = false;

		for (final Vector<BuildingProject> projectLevel : buildingProjects) {

			boolean includedNewBuildings = false;

			for (final BuildingProject project : projectLevel) {

				if (((project.location == null) || (project.location.level < 0))
						&& !foundNewBuildingsLevel) {
					if (isValidProject(project)) {
						possibleProjects.add(project);
						includedNewBuildings = true;
						if (MLN.LogBuildingPlan >= MLN.DEBUG) {
							MLN.debug(this, "Found a new building to add: "
									+ project);
						}
						if ((MLN.LogBuildingPlan >= MLN.MINOR)
								&& (project.getChoiceWeight(null) < 1)) {
							MLN.minor(this, "Project has null or negative weight: "
									+ project + ": " + project.getChoiceWeight(null));
						}
					}
				} else if (((project.location != null) && (project.location.level >= 0))
						&& (project.location.level < project
								.getLevelsNumber(project.location.getVariation()))
								&& project.location.upgradesAllowed
								&& (project.getChoiceWeight(null) > 0)) {
					possibleProjects.add(project);
				}
			}

			if (includedNewBuildings) {
				foundNewBuildingsLevel = true;
			}
		}

		if (possibleProjects.size() == 0) {
			noProjectsLeft = true;
			return;
		}

		final BuildingProject project = BuildingProject
				.getRandomProject(possibleProjects);
		final BuildingPlan plan = project.getNextBuildingPlan();

		buildingGoal = project.key;
		buildingGoalLevel = plan.level;
		buildingGoalVariation = plan.variation;
		if (project.location == null) {
			buildingGoalLocation = null;
		} else {
			buildingGoalLocation = project.location
					.createLocationForLevel(buildingGoalLevel);
		}

		if (MLN.LogBuildingPlan >= MLN.MAJOR) {
			MLN.major(this, "Picked new upgrade goal: " + buildingGoal
					+ " level: " + buildingGoalLevel
					+ " buildingGoalLocation: " + buildingGoalLocation);
		}
	}

	public void findName(String pname) {

		if (pname != null) {
			name = pname;
		} else {
			if (villageType.nameList==null) {
				name=null;
				return;
			}
			name = culture.getRandomNameFromList(villageType.nameList);
		}

		final Vector<String> qualifiers = new Vector<String>();

		for (final String s : villageType.qualifiers) {
			qualifiers.add(s);
		}

		if ((villageType.hillQualifier != null) && (pos.getiY() > 75) && (pos.getiY() < 85)) {
			qualifiers.add(villageType.hillQualifier);
		} else if ((villageType.mountainQualifier != null) && (pos.getiY() >= 85)) {
			qualifiers.add(villageType.mountainQualifier);
		}

		if ((villageType.desertQualifier != null)
				|| (villageType.forestQualifier != null)
				|| (villageType.lavaQualifier != null)
				|| (villageType.lakeQualifier != null)
				|| (villageType.oceanQualifier != null)) {
			int cactus = 0, wood = 0, lake = 0, ocean = 0, lava = 0;
			for (int i = -50; i < 50; i++) {
				for (int j = -10; j < 20; j++) {
					for (int k = -50; k < 50; k++) {
						final int bid = worldObj.getBlockId(i + pos.getiX(), j
								+ pos.getiY(), k + pos.getiZ());
						if (bid == Block.cactus.blockID) {
							cactus++;
						} else if (bid == Block.wood.blockID) {
							wood++;
						} else if (bid == Block.lavaStill.blockID) {
							lava++;
						} else if (bid == Block.waterStill.blockID) {
							if (worldObj.getBlockId(i + pos.getiX(),
									j + pos.getiY() + 1, k + pos.getiZ()) == 0) {
								if ((j + pos.getiY()) < 65) {
									ocean++;
								} else {
									lake++;
								}
							}
						}
					}
				}
			}
			if ((villageType.desertQualifier != null) && (cactus > 0)) {
				qualifiers.add(villageType.desertQualifier);
			}
			if ((villageType.forestQualifier != null) && (wood > 40)) {
				qualifiers.add(villageType.forestQualifier);
			}
			if ((villageType.lavaQualifier != null) && (lava > 0)) {
				qualifiers.add(villageType.lavaQualifier);
			}
			if ((villageType.lakeQualifier != null) && (lake > 0)) {
				qualifiers.add(villageType.lakeQualifier);
			}
			if ((villageType.oceanQualifier != null) && (ocean > 0)) {
				qualifiers.add(villageType.oceanQualifier);
			}
		}

		if (qualifiers.size() > 0) {
			qualifier = qualifiers.get(MillCommonUtilities.randomInt(qualifiers.size()));
		} else {
			qualifier = "";
		}
	}

	public int getAltitude(int x, int z) {

		if (winfo == null)
			return -1;

		if ((x < winfo.mapStartX) || (x >= (winfo.mapStartX + winfo.length))
				|| (z < winfo.mapStartZ)
				|| (z >= (winfo.mapStartZ + winfo.width)))
			return -1;

		return winfo.topGround[x - winfo.mapStartX][z - winfo.mapStartZ];
	}

	public BuildingBlock[] getBblocks() {
		return bblocks;
	}

	public Building getBuildingAtCoord(Point p) {

		for (final Building b : getBuildings()) {
			if (b.location.isInside(p))
				return b;
		}

		return null;
	}

	public Vector<Building> getBuildings() {
		final Vector<Building> vbuildings = new Vector<Building>();

		for (final Point p : buildings) {
			final Building building = mw.getBuilding(p);
			if ((building != null) && (building.location != null)) {
				vbuildings.add(building);
			}
		}
		return vbuildings;
	}

	public Vector<Building> getBuildingsWithTag(String s) {
		final Vector<Building> matches = new Vector<Building>();
		for (final Point p : buildings) {
			final Building building = mw.getBuilding(p);
			if ((building != null) && (building.location != null)
					&& (building.location.tags != null)
					&& building.location.tags.contains(s)) {
				matches.add(building);
			}
		}
		return matches;
	}

	public Vector<Goods> getBuyingGoods() {

		if (!culture.shopBuys.containsKey(location.shop))
			return null;

		if (isTownhall) {
			final BuildingPlan goalPlan = getCurrentGoalBuildingPlan();

			if (goalPlan != null) {

				final Vector<Goods> baseGoods = culture.shopBuys.get(location.shop);

				final Vector<Goods> extraGoods = new Vector<Goods>();

				if (goalPlan != null) {
					for (final InvItem key : goalPlan.resCost.keySet()) {
						if (key.meta != -1) {
							boolean found = false;
							for (final Goods tg : baseGoods) {
								if ((tg.item.id() == key.id())
										&& (tg.item.meta == key.meta)) {
									found = true;
								}
							}
							if (!found) {
								if (culture.goodsByItem.containsKey(key)) {
									extraGoods.add(culture.goodsByItem.get(key));
								} else {
									extraGoods.add(new Goods(key));
								}
							}
						}
					}
				}
				if (extraGoods.size() == 0)
					return baseGoods;
				else {
					final Vector<Goods> finalGoods = new Vector<Goods>();

					for (final Goods good : baseGoods) {
						finalGoods.add(good);
					}

					for (final Goods good : extraGoods) {
						finalGoods.add(good);
					}

					return finalGoods;
				}

			}
		}
		return culture.shopBuys.get(location.shop);
	}

	public HashMap<InvItem, Integer> getChestsContent() {

		final HashMap<InvItem, Integer> contents = new HashMap<InvItem, Integer>();

		for (final Point p : chests) {
			final TileEntityChest chest = p.getMillChest(worldObj);
			if (chest != null) {
				for (int i = 0; i < chest.getSizeInventory(); i++) {
					final ItemStack stack = chest.getStackInSlot(i);
					if (stack != null) {
						final InvItem key = new InvItem(stack);
						if (stack != null) {
							if (contents.containsKey(key)) {
								contents.put(key,
										stack.stackSize + contents.get(key));
							} else {
								contents.put(key, stack.stackSize);
							}
						}
					}
				}
			}
		}
		return contents;
	}

	public Point getClosestBlockAround(Point p, int[] blockIds, int hlimit,
			int vstart, int vend) {

		if (pathing == null)
			return null;

		final int cx = p.getiX();
		final int cz = p.getiZ();
		int x = cx, z = cz;
		int dir = 3, radius = 0;

		vstart = p.getiY() + vstart;
		vend = p.getiY() + vend;

		while (radius < hlimit) {

			if (((x > winfo.mapStartX) && (x < (winfo.mapStartX + winfo.length)))
					&& ((z > winfo.mapStartZ) && (z < (winfo.mapStartZ + winfo.width)))) {
				for (int i = vend; i >= vstart; i--) {
					final int bid = worldObj.getBlockId(x, i, z);
					for (final int id : blockIds) {
						if (bid == id) {
							final Point np = new Point(x, i, z);
							if (pathing.isValidPoint(np))
								return np;
						}
					}
				}
			}
			if (dir == 0) {
				if (((x - cx) < radius)) {
					x++;
				} else {
					dir = 1;
					z++;
				}
			} else if (dir == 1) {
				if (((z - cz) < radius)) {
					z++;
				} else {
					dir = 2;
					x--;
				}
			} else if (dir == 2) {
				if (((cx - x) < radius)) {
					x--;
				} else {
					dir = 3;
					z--;
				}
			} else {
				if (((cz - z) < radius)) {
					z--;
				} else {
					dir = 0;
					radius++;
					x = cx - radius;
					z = cz - radius;
				}
			}
		}

		return null;
	}

	public Point getCraftingPos() {
		if (craftingPos != null)
			return craftingPos;

		if (sellingPos != null)
			return sellingPos;

		return sleepingPos;
	}

	public BuildingBlock getCurrentBlock() {
		if (bblocks==null)
			return null;

		if (bblocksPos>=bblocks.length)
			return null;

		return bblocks[bblocksPos];
	}

	public BuildingPlan getCurrentBuildingPlan() {
		if (buildingLocationIP == null)
			return null;

		for (final Vector<BuildingProject> projectsLevel : buildingProjects) {
			for (final BuildingProject project : projectsLevel) {
				if ((buildingLocationIP.level == 0)
						&& ((project.location == null) || (project.location.level < 0))
						&& project.key.equals(buildingLocationIP.key)) {
					if (MLN.LogBuildingPlan >= MLN.DEBUG) {
						MLN.debug(
								this,
								"Returning building plan for "
										+ buildingLocationIP
										+ ": "
										+ project.getPlan(
												buildingLocationIP.getVariation(),
												buildingLocationIP.level));
					}
					return project.getPlan(buildingLocationIP.getVariation(),
							buildingLocationIP.level);
				} else if (buildingLocationIP.isSameLocation(project.location)) {
					if (MLN.LogBuildingPlan >= MLN.DEBUG) {
						MLN.debug(
								this,
								"Returning building plan for "
										+ buildingLocationIP
										+ ": "
										+ project.getPlan(
												buildingLocationIP.getVariation(),
												buildingLocationIP.level));
					}
					return project.getPlan(buildingLocationIP.getVariation(),
							buildingLocationIP.level);
				}
			}
		}

		MLN.error(this, "Could not find plan for current building location: "
				+ buildingLocationIP);
		return null;
	}

	public Point getCurrentClearPathPoint() {

		if (oldPathPointsToClear==null)
			return null;

		if (oldPathPointsToClearIndex>=oldPathPointsToClear.size()) {
			oldPathPointsToClear=null;
			return null;
		}

		return oldPathPointsToClear.get(oldPathPointsToClearIndex);
	}

	public BuildingPlan getCurrentGoalBuildingPlan() {
		if (buildingGoal == null)
			return null;

		for (final Vector<BuildingProject> projectsLevel : buildingProjects) {
			for (final BuildingProject project : projectsLevel) {
				if (project.key.equals(buildingGoal)) {
					if (buildingGoalLocation == null)
						return project.getPlan(buildingGoalVariation, 0);
					else
						return project.getPlan(buildingGoalVariation,
								buildingGoalLocation.level);
				}
			}
		}
		return null;
	}


	public BuildingBlock getCurrentPathBuildingBlock() {

		if (pathsToBuild==null)
			return null;

		while (true) {
			if (pathsToBuildIndex>=pathsToBuild.size()) {
				pathsToBuild=null;
				return null;
			} else if (pathsToBuildPathIndex>=pathsToBuild.get(pathsToBuildIndex).size()) {
				pathsToBuildIndex++;
				pathsToBuildPathIndex=0;
			} else {
				final BuildingBlock b=pathsToBuild.get(pathsToBuildIndex).get(pathsToBuildPathIndex);

				final int bid=b.p.getId(worldObj);
				final int meta=b.p.getMeta(worldObj);

				if (MillCommonUtilities.canPathBeBuiltHere(bid,meta) && ((bid!=b.bid) || (meta!=b.meta)))
					return b;

				pathsToBuildPathIndex++;
			}
		}
	}

	//Where to regroup to defend the village
	public Point getDefendingPos() {
		if (defendingPos != null)
			return defendingPos;

		if (sellingPos != null)
			return sellingPos;

		return sleepingPos;
	}

	public Point getEmptyBrickLocation() {
		if (brickspot.size() == 0)
			return null;

		final int start = MillCommonUtilities.randomInt(brickspot.size());
		for (int i = start; i < brickspot.size(); i++) {
			final Point p = brickspot.get(i);
			if (MillCommonUtilities.getBlock(worldObj, p) == 0)
				return p;
		}

		for (int i = 0; i < start; i++) {
			final Point p = brickspot.get(i);
			if (MillCommonUtilities.getBlock(worldObj, p) == 0)
				return p;
		}

		return null;
	}

	public Building getFirstBuildingWithTag(String s) {

		for (final Point p : buildings) {
			final Building building = mw.getBuilding(p);
			if ((building != null) && (building.location != null)
					&& (building.location.tags != null)
					&& building.location.tags.contains(s))
				return building;
		}
		return null;
	}

	public Vector<BuildingProject> getFlatProjectList() {
		final Vector<BuildingProject> projects = new Vector<BuildingProject>();

		for (final Vector<BuildingProject> projectLevel : buildingProjects) {
			for (final BuildingProject project : projectLevel) {
				projects.add(project);
			}
		}
		return projects;
	}

	public Point getFullBrickLocation() {
		if (brickspot.size() == 0)
			return null;

		final int start = MillCommonUtilities.randomInt(brickspot.size());
		for (int i = start; i < brickspot.size(); i++) {
			final Point p = brickspot.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p) == Mill.stone_decoration.blockID)
					&& (MillCommonUtilities.getBlockMeta(worldObj, p) == 1))
				return p;
		}

		for (int i = 0; i < start; i++) {
			final Point p = brickspot.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p) == Mill.stone_decoration.blockID)
					&& (MillCommonUtilities.getBlockMeta(worldObj, p) == 1))
				return p;
		}

		return null;
	}


	public String getGameBuildingName() {
		return location.getPlan().getGameName();
	}

	public HashMap<Goods, Integer> getImportsNeededbyOtherVillages() {

		if ((neededGoodsCached != null)
				&& (System.currentTimeMillis() < (neededGoodsLastGenerated + 60000)))
			return neededGoodsCached;

		neededGoodsCached = new HashMap<Goods, Integer>();

		for (final Point vp : mw.villagesList.pos) {
			final Chunk chunk = worldObj.getChunkFromBlockCoords(vp.getiX(),
					vp.getiZ());
			if (chunk.isChunkLoaded) {
				final Building townHall = mw.getBuilding(vp);
				if ((townHall != null) && (getTownHall() != null)
						&& (townHall.villageType != getTownHall().villageType)
						&& (townHall.culture == getTownHall().culture)) {
					if (townHall.getBuildingsWithTag(tagInn).size() > 0) {
						townHall.getNeededImportGoods(neededGoodsCached);
					}
				}
			}
		}

		neededGoodsLastGenerated = System.currentTimeMillis();

		return neededGoodsCached;
	}

	public Set<Point> getKnownVillages() {
		return relations.keySet();
	}


	public BuildingLocation getLocationAtCoord(Point p) {
		if ((buildingLocationIP!=null) && buildingLocationIP.isInside(p))
			return buildingLocationIP;

		for (final BuildingLocation bl : getLocations()) {
			if (bl.isInside(p))
				return bl;
		}

		return null;
	}

	public Vector<BuildingLocation> getLocations() {

		final Vector<BuildingLocation> locations = new Vector<BuildingLocation>();

		for (final Vector<BuildingProject> projectsLevel : buildingProjects) {
			for (final BuildingProject project : projectsLevel) {
				if (project.location != null) {
					locations.add(project.location);
				}
			}
		}

		return locations;

	}

	public String getNativeBuildingName() {
		if (location.getPlan()==null)
			return "";

		return location.getPlan().nativeName;
	}


	public int getNbEmptyBrickLocation() {
		if (brickspot.size() == 0)
			return 0;

		int nb = 0;

		for (int i = 0; i < brickspot.size(); i++) {
			final Point p = brickspot.get(i);
			if (MillCommonUtilities.getBlock(worldObj, p) == 0) {
				nb++;
			}
		}

		return nb;
	}

	public int getNbFullBrickLocation() {
		if (brickspot.size() == 0)
			return 0;

		int nb = 0;

		for (int i = 0; i < brickspot.size(); i++) {
			final Point p = brickspot.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p) == Mill.stone_decoration.blockID)
					&& (MillCommonUtilities.getBlockMeta(worldObj, p) == 1)) {
				nb++;
			}
		}

		return nb;
	}

	public int getNbNetherWartHarvestLocation() {
		if (netherwartsoils.size() == 0)
			return 0;

		int nb = 0;
		for (int i = 0; i < netherwartsoils.size(); i++) {
			final Point p = netherwartsoils.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p.getAbove()) == Block.netherStalk.blockID)
					&& (MillCommonUtilities.getBlockMeta(worldObj, p.getAbove()) >= 3)) {
				nb++;
			}
		}

		return nb;
	}

	public int getNbNetherWartPlantingLocation() {
		if (netherwartsoils.size() == 0)
			return 0;

		int nb = 0;
		for (int i = 0; i < netherwartsoils.size(); i++) {
			final Point p = netherwartsoils.get(i);
			if (MillCommonUtilities.getBlock(worldObj, p.getAbove()) == 0) {
				nb++;
			}
		}

		return nb;
	}

	public int getNbProjects() {
		int nb = 0;

		for (final Vector<BuildingProject> projects : buildingProjects) {
			nb += projects.size();
		}

		return nb;
	}


	public int getNbSilkWormHarvestLocation() {
		if (silkwormblock.size() == 0)
			return 0;

		int nb = 0;
		for (int i = 0; i < silkwormblock.size(); i++) {
			final Point p = silkwormblock.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p) == Mill.wood_decoration.blockID)
					&& (MillCommonUtilities.getBlockMeta(worldObj, p) == 4)) {
				nb++;
			}
		}

		return nb;
	}

	public int getNbSugarCaneHarvestLocation() {
		if (sugarcanesoils.size() == 0)
			return 0;

		int nb = 0;
		for (int i = 0; i < sugarcanesoils.size(); i++) {
			final Point p = sugarcanesoils.get(i);
			if (MillCommonUtilities.getBlock(worldObj, p.getRelative(0, 2, 0)) == Block.reed.blockID) {
				nb++;
			}
		}

		return nb;
	}

	public int getNbSugarCanePlantingLocation() {
		if (sugarcanesoils.size() == 0)
			return 0;

		int nb = 0;
		for (int i = 0; i < sugarcanesoils.size(); i++) {
			final Point p = sugarcanesoils.get(i);
			if (MillCommonUtilities.getBlock(worldObj, p.getAbove()) == 0) {
				nb++;
			}
		}

		return nb;
	}

	public void getNeededImportGoods(HashMap<Goods, Integer> neededGoods) {

		for (final Goods good : culture.goodsVector) {
			final int nbneeded = nbGoodNeeded(good.item.id(), good.item.meta);
			if (nbneeded > 0) {
				if (MLN.LogMerchant >= MLN.DEBUG) {
					MLN.debug(this, "Import needed: " + good.getName() + " - "
							+ nbneeded);
				}

				if (neededGoods.containsKey(good)) {
					neededGoods.put(good, neededGoods.get(good) + nbneeded);
				} else {
					neededGoods.put(good, nbneeded);
				}
			}
		}
	}

	public Point getNetherWartsHarvestLocation() {
		if (netherwartsoils.size() == 0)
			return null;

		final int start = MillCommonUtilities.randomInt(netherwartsoils.size());
		for (int i = start; i < netherwartsoils.size(); i++) {
			final Point p = netherwartsoils.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p.getAbove()) == Block.netherStalk.blockID)
					&& (MillCommonUtilities.getBlockMeta(worldObj, p.getAbove()) == 3))
				return p;
		}

		for (int i = 0; i < start; i++) {
			final Point p = netherwartsoils.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p.getAbove()) == Block.netherStalk.blockID)
					&& (MillCommonUtilities.getBlockMeta(worldObj, p.getAbove()) == 3))
				return p;
		}

		return null;
	}

	public Point getNetherWartsPlantingLocation() {
		if (netherwartsoils.size() == 0)
			return null;

		final int start = MillCommonUtilities.randomInt(netherwartsoils.size());
		for (int i = start; i < netherwartsoils.size(); i++) {
			final Point p = netherwartsoils.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p.getAbove()) == 0) && (MillCommonUtilities.getBlock(worldObj, p) == Block.slowSand.blockID))
				return p;
		}

		for (int i = 0; i < start; i++) {
			final Point p = netherwartsoils.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p.getAbove()) == 0) && (MillCommonUtilities.getBlock(worldObj, p) == Block.slowSand.blockID))
				return p;
		}

		return null;
	}

	public int getNewGender() {

		int nbmales = 0, nbfemales = 0;

		for (final VillagerRecord vr : vrecords) {
			if (vr.gender == MillVillager.MALE) {
				nbmales++;
			} else {
				nbfemales++;
			}
		}

		final int maleChance = (3 + nbfemales) - nbmales;

		return (MillCommonUtilities.randomInt(6) < maleChance) ? MillVillager.MALE
				: MillVillager.FEMALE;
	}

	public Point getPathStartPos() {

		if (pathStartPos!=null)
			return pathStartPos;

		return getSellingPos();
	}

	public Point getLeasurePos() {

		if (leasurePos!=null)
			return leasurePos;

		return getSellingPos();
	}

	public Point getPlantingLocation() {
		for (final Point p : woodspawn) {
			final int bid = MillCommonUtilities.getBlock(worldObj, p);
			if ((bid == 0) || (bid == Block.snow.blockID))
				return p;
		}
		return null;
	}

	public Point getPos() {
		return pos;
	}

	public Point getRandomLocationWithTag(String tag) {
		final Vector<Point> v = new Vector<Point>();

		for (final BuildingLocation l : getLocations()) {
			if ((l.level >= 0) && l.tags.contains(tag)
					&& (l.sleepingPos != null)) {
				v.add(l.sleepingPos);
			}
		}

		if (v.size() == 0)
			return null;

		return (v.get(MillCommonUtilities.randomInt(v.size())));
	}

	public int getRelationWithVillage(Point p) {
		if (relations.containsKey(p))
			return relations.get(p);
		return 0;
	}

	public int getReputation(String playerName) {
		return mw.getProfile(playerName).getReputation(this);
	}

	public String getReputationLevelLabel(String playerName) {
		return culture.getReputationLevelLabel(getReputation(playerName));
	}

	public String getReputationLevelDesc(String playerName) {
		return culture.getReputationLevelDesc(getReputation(playerName));
	}

	public Vector<Goods> getSellingGoods() {
		return culture.shopSells.get(location.shop);
	}

	public Point getSellingPos() {
		if (sellingPos != null)
			return sellingPos;

		return sleepingPos;
	}

	//Where to take shelter in case of raid
	//Defaults to sleeping pos unless specified
	public Point getShelterPos() {
		if (shelterPos != null)
			return shelterPos;

		return sleepingPos;
	}

	public Building getShop(String shop) {
		for (final BuildingLocation l : getLocations()) {
			if ((l.level >= 0) && shop.equals(l.shop))
				return l.getBuilding(worldObj);
		}

		return null;
	}

	public Point getShopPos(String shop) {
		for (final BuildingLocation l : getLocations()) {
			if ((l.level >= 0) && shop.equals(l.shop))
				return l.chestPos;
		}

		return null;
	}

	public Vector<Building> getShops() {
		final Vector<Building> shops = new Vector<Building>();

		for (final Point p : buildings) {
			final Building building = mw.getBuilding(p);
			if ((building != null) && (building.location != null)
					&& (building.location.shop != null)
					&& (building.location.shop.length() > 0)) {
				shops.add(building);
			}
		}
		return shops;
	}

	public Point getSilkwormHarvestLocation() {
		if (silkwormblock.size() == 0)
			return null;

		final int start = MillCommonUtilities.randomInt(silkwormblock.size());
		for (int i = start; i < silkwormblock.size(); i++) {
			final Point p = silkwormblock.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p) == Mill.wood_decoration.blockID)
					&& (MillCommonUtilities.getBlockMeta(worldObj, p) == 4))
				return p;
		}

		for (int i = 0; i < start; i++) {
			final Point p = silkwormblock.get(i);
			if ((MillCommonUtilities.getBlock(worldObj, p) == Mill.wood_decoration.blockID)
					&& (MillCommonUtilities.getBlockMeta(worldObj, p) == 4))
				return p;
		}

		return null;
	}


	public Point getSleepingPos() {
		return sleepingPos;
	}

	public Vector<Point> getSoilPoints(String type) {

		for (int i=0;i<soilTypes.size();i++) {
			if (soilTypes.get(i).equals(type))
				return soils.get(i);
		}

		return null;
	}

	public Point getSugarCaneHarvestLocation() {
		if (sugarcanesoils.size() == 0)
			return null;

		final int start = MillCommonUtilities.randomInt(sugarcanesoils.size());
		for (int i = start; i < sugarcanesoils.size(); i++) {
			final Point p = sugarcanesoils.get(i);
			if (MillCommonUtilities.getBlock(worldObj, p.getRelative(0, 2, 0)) == Block.reed.blockID)
				return p;
		}

		for (int i = 0; i < start; i++) {
			final Point p = sugarcanesoils.get(i);
			if (MillCommonUtilities.getBlock(worldObj, p.getRelative(0, 2, 0)) == Block.reed.blockID)
				return p;
		}

		return null;
	}

	public Point getSugarCanePlantingLocation() {
		if (sugarcanesoils.size() == 0)
			return null;

		final int start = MillCommonUtilities.randomInt(sugarcanesoils.size());
		for (int i = start; i < sugarcanesoils.size(); i++) {
			final Point p = sugarcanesoils.get(i);
			if (MillCommonUtilities.getBlock(worldObj, p.getAbove()) == 0)
				return p;
		}

		for (int i = 0; i < start; i++) {
			final Point p = sugarcanesoils.get(i);
			if (MillCommonUtilities.getBlock(worldObj, p.getAbove()) == 0)
				return p;
		}

		return null;
	}

	public Building getTownHall() {
		if (townHallPos == null)
			return null;

		return mw.getBuilding(townHallPos);
	}

	public int getVillageAttackerStrength() {
		int strength=0;

		for (final VillagerRecord vr : vrecords) {
			if (vr.raidingVillage && !vr.killed) {
				strength+=vr.getMilitaryStrength();
			}
		}

		return strength;
	}

	public int getVillageDefendingStrength() {
		int strength=0;

		for (final VillagerRecord vr : vrecords) {
			if ((vr.getType()!=null) && vr.getType().helpInAttacks && !vr.killed && !vr.raidingVillage) {
				strength+=vr.getMilitaryStrength();
			}
		}

		return strength;
	}

	public int getVillageIrrigation() {

		int irrigation=0;

		for (final BuildingLocation bl : getLocations()) {
			if (bl.getPlan()!=null) {
				irrigation+=bl.getPlan().irrigation;
			}
		}

		return irrigation;
	}

	public void changeVillageName(String s) {
		name=s;
	}
	public void changeVillageQualifier(String s) {
		qualifier=s;
	}

	public String getVillageNameWithoutQualifier() {
		if ((name==null) || (name.length()==0)) {
			if (villageType!=null)
				return villageType.name;
			else
				return getNativeBuildingName();
		}

		return name;
	}

	public String getVillageQualifiedName() {

		if ((name==null) || (name.length()==0)) {
			if (villageType!=null)
				return villageType.name;
			else
				return getNativeBuildingName();
		}

		if ((qualifier == null) || (qualifier.length() == 0))
			return name;
		else
			return name + culture.qualifierSeparator + qualifier;
	}

	public int getVillageRaidingStrength() {
		int strength=0;

		for (final VillagerRecord vr : vrecords) {
			if ((vr.getType()!=null) && vr.getType().isRaider && !vr.killed && !vr.raidingVillage) {
				strength+=vr.getMilitaryStrength();
			}
		}

		return strength;
	}

	public MillVillager getVillagerById(long id) {

		for (final MillVillager v : villagers) {
			if (v.villager_id == id)
				return v;
		}

		return null;
	}

	public VillagerRecord getVillagerRecordById(long id) {

		for (final VillagerRecord vr : vrecords) {
			if (vr.id == id)
				return vr;
		}

		return null;
	}

	public int getWoodCount() {
		if (!location.tags.contains(tagGrove))
			return 0;

		int nb = 0;

		for (int i = location.minx - 1; i < (location.maxx + 1); i++) {
			for (int j = location.pos.getiY() - 1; j < (location.pos.getiY() + 10); j++) {
				for (int k = location.minz - 1; k < (location.maxz + 1); k++) {
					if (worldObj.getBlockId(i, j, k) == Block.wood.blockID) {
						nb++;
					}
				}
			}
		}
		return nb;
	}

	public Point getWoodLocation() {
		if (!location.tags.contains(tagGrove))
			return null;

		for (int i = location.minx - 1; i < (location.maxx + 1); i++) {
			for (int j = location.pos.getiY() - 1; j < (location.pos.getiY() + 10); j++) {
				for (int k = location.minz - 1; k < (location.maxz + 1); k++) {
					if (worldObj.getBlockId(i, j, k) == Block.wood.blockID)
						return new Point(i, j, k);
				}
			}
		}
		return null;
	}

	public void growTree(World world, int i, int j, int k, Random random) {
		final int meta = world.getBlockMetadata(i, j, k) & 3;
		MillCommonUtilities.setBlockAndMetadata(worldObj, i,j,k, 0, 0, true, false);
		WorldGenerator obj = null;
		if (meta == 1) {
			obj = new WorldGenTaiga2(true);
		} else if (meta == 2) {
			obj = new WorldGenForest(true);
		} else if (meta == 3) {
			obj = new WorldGenTrees(true, 4, 3, 3, false);
		} else {
			obj = new WorldGenTrees(true);
		}
		obj.generate(world, random, i, j, k);
	}

	public void incrementBblockPos() {
		bblocksPos++;

		if (!areBlocksLeft()) {
			bblocks=null;
			bblocksPos=0;
			bblocksChanged=true;
		}
	}

	public void initialise(EntityPlayer owner,boolean villageCreation) {
		if (MLN.LogWorldGeneration >= MLN.MAJOR) {
			MLN.major(this, "Initialising building at " + getPos()+", TH pos: "+townHallPos+", TH: "+getTownHall());
		}

		if (isHouse()) {
			try {
				initialiseHouse(villageCreation);
			} catch (final MillenaireException e) {
				MLN.printException("Error when trying to create a building: ",
						e);
			}
			updateHouseSign();
		}

		if (isTownhall) {
			initialiseTownHall(owner);
		} else {
			chestLocked = getTownHall().chestLocked;
			if (!chestLocked) {
				unlockChests();
			}
		}
	}

	public void initialiseBuildingProjects() {
		if (villageType == null) {
			MLN.error(this, "villageType is null!");
			return;
		}
		buildingProjects = villageType.getBuildingProjects();
	}

	public void initialiseCurrentConstruction(Point refPos) throws MillenaireException {
		boolean isTownHall=false;
		if (buildingLocationIP.equals(location)) {
			isTownHall=true;
		}


		Building building;

		if (buildingLocationIP.level == 0) {
			building=new Building(mw, culture, villageType, buildingLocationIP, isTownHall, false, refPos, getPos());
		} else {
			building=mw.getBuilding(refPos);
		}

		final BuildingPlan plan=getCurrentBuildingPlan();

		plan.referenceBuildingPoints(worldObj, building, buildingLocationIP);

		if (buildingLocationIP.level == 0) {
			building.initialise(null,false);
			registerBuildingEntity(building);
			if (MLN.LogBuildingPlan>=MLN.MAJOR) {
				MLN.major(this, "Created new Building Entity: "+plan.planName+" at "+refPos);
			}
		}

		completeConstruction();
	}

	private void initialiseHouse(boolean villageCreation)
			throws MillenaireException {
		if (villageCreation) {
			createResidents();
		}
	}

	public void initialiseRelations(Point parentVillage) {

		if (villageType.lonebuilding)
			return;

		this.parentVillage=parentVillage;

		for (final Point p : mw.villagesList.pos) {
			if (!pos.sameBlock(p) && (pos.distanceToSquared(p) < (MLN.BackgroundRadius*MLN.BackgroundRadius))) {
				final Building distantVillage=mw.getBuilding(p);
				if (distantVillage!=null) {
					//First case: hamlets & parent village
					if ((parentVillage!=null) && (p.sameBlock(parentVillage) || parentVillage.sameBlock(distantVillage.parentVillage))) {
						adjustRelation(p,RELATION_MAX);
						//Player-controlled villages have max relations with each-other:
					} else if (villageType.playerControlled && (controlledBy.equals(distantVillage.controlledBy))) {
						adjustRelation(p,RELATION_MAX);
					} else if (distantVillage.culture==culture) {
						adjustRelation(p,RELATION_GOOD);
					} else {
						adjustRelation(p,RELATION_BAD);
					}
				}
			}
		}

	}

	public void initialiseTownHall(EntityPlayer controller) {

		if (name == null) {
			findName(null);
		}

		if (MLN.LogWorldGeneration >= MLN.MAJOR) {
			MLN.major(this, "Initialising town hall: " + getVillageQualifiedName());
		}

		buildings.add(getPos());

		if (villageType.playerControlled && (controller!=null)) {
			final UserProfile profile=mw.getProfile(controller.username);
			controlledBy=profile.key;
			profile.adjustReputation(this, 32 * 64 * 64);
		}


	}

	public void initialiseVillage() {
		boolean noMenLeft = true;

		for (int i = vrecords.size() - 1; i >= 0; i--) {
			final VillagerRecord vr = vrecords.get(i);
			if ((vr.gender == MillVillager.MALE) && !vr.getType().isChild) {
				noMenLeft = false;
			}
		}

		for (final Point p : buildings) {
			final Building b = mw.getBuilding(p);
			if (b != null) {
				if (noMenLeft) {
					b.unlockChests();
				} else {
					b.lockChests();
				}
			}
		}


		recalculatePaths(true);

	}

	public boolean isDisplayableProject(BuildingProject project) {
		if ((project.getPlan(0, 0).requiredTag!=null)) {
			if (!mw.isGlobalTagSet(project.getPlan(0, 0).requiredTag))
				return false;
		} else if (project.getPlan(0, 0).isgift && !MLN.bonusEnabled) {
			return false;
		}
		return true;
	}

	public boolean isHouse() {
		return ((location != null) && ((location.maleResident.size() > 0) || (location.femaleResident
				.size() > 0)));
	}

	public boolean isPointProtectedFromPathBuilding(Point p) {
		final Point above=p.getAbove(),below=p.getBelow();

		for (final Building b : getBuildings()) {
			if ((b.location!=null) && b.location.isInside(p)) {

				if (b.location.tags.contains(tagNoPaths))
					return true;

				if (b.soils!=null) {
					for (final Vector<Point> vpoints : b.soils) {
						if (vpoints.contains(p) || vpoints.contains(above) || vpoints.contains(below))
							return true;
					}
				}

				if (b.sources!=null) {
					for (final Vector<Point> vpoints : b.sources) {
						if (vpoints.contains(p) || vpoints.contains(above) || vpoints.contains(below))
							return true;
					}
				}
			}
		}

		return false;
	}

	public boolean isReachableFromRegion(byte regionId) {

		if (getTownHall().pathing==null)//on client in MP
			return true;

		if (getTownHall().pathing.regions[getSleepingPos().getiX()-getTownHall().winfo.mapStartX][getSleepingPos().getiZ()-getTownHall().winfo.mapStartZ]!=regionId)
			return false;

		if (getTownHall().pathing.regions[getSellingPos().getiX()-getTownHall().winfo.mapStartX][getSellingPos().getiZ()-getTownHall().winfo.mapStartZ]!=regionId)
			return false;

		if (getTownHall().pathing.regions[getDefendingPos().getiX()-getTownHall().winfo.mapStartX][getDefendingPos().getiZ()-getTownHall().winfo.mapStartZ]!=regionId)
			return false;

		if (getTownHall().pathing.regions[getShelterPos().getiX()-getTownHall().winfo.mapStartX][getShelterPos().getiZ()-getTownHall().winfo.mapStartZ]!=regionId)
			return false;

		return true;
	}

	public boolean isValidProject(BuildingProject project) {

		if ((!villageType.playerControlled
				&& (project.getPlan(0, 0).price > 0 || project.getPlan(0, 0).isgift) && !buildingsBought.contains(project.key)))
			return false;

		if ((project.getPlan(0, 0).requiredTag!=null) && !mw.isGlobalTagSet(project.getPlan(0, 0).requiredTag))
			return false;

		return true;
	}

	private boolean isVillageChunksLoaded() {
		for (int x=winfo.mapStartX;x<(winfo.mapStartX+winfo.width);x+=16) {
			for (int z=winfo.mapStartZ;z<(winfo.mapStartZ+winfo.length);z+=16) {
				if (!worldObj.getChunkProvider().chunkExists(x/16,
						z/16))
					return false;

				if (!worldObj.getChunkFromBlockCoords(x, z).isChunkLoaded)
					return false;
			}
		}
		return true;
	}

	private void killMobs() {

		if (winfo == null)
			return;

		for (final Point buildingPos : buildings) {

			final Building b=mw.getBuilding(buildingPos);

			if ((b!=null) && (b.location!=null)) {

				final int radius=(Math.max(b.location.length, b.location.width)/2)+8;

				final Point start = new Point(b.location.pos.x-radius, b.location.pos.getiY() - 10,
						b.location.pos.z-radius);
				final Point end = new Point(b.location.pos.x+radius, b.location.pos.getiY() + 30,
						b.location.pos.z+radius);

				if (location.tags.contains(tagDespawnAllMobs)) {
					final List<Entity> mobs = MillCommonUtilities.getEntitiesWithinAABB(worldObj,
							EntityMob.class, start, end);

					for (final Entity ent : mobs) {
						if (!ent.isDead) {
							if (MLN.LogTileEntityBuilding >= MLN.DEBUG) {
								MLN.debug(this, "Killing mob " + ent + " at "
										+ ent.posX + "/" + ent.posY + "/" + ent.posZ);
							}
							ent.setDead();
						}
					}
				} else {
					final List<Entity> creepers = MillCommonUtilities.getEntitiesWithinAABB(worldObj,
							EntityCreeper.class, start, end);

					for (final Entity ent : creepers) {
						if (!ent.isDead) {
							if (MLN.LogTileEntityBuilding >= MLN.DEBUG) {
								MLN.debug(this, "Killing creeper " + ent + " at "
										+ ent.posX + "/" + ent.posY + "/" + ent.posZ);
							}
							ent.setDead();
						}
					}

					final List<Entity> endermen = MillCommonUtilities.getEntitiesWithinAABB(worldObj,
							EntityEnderman.class, start, end);

					for (final Entity ent : endermen) {
						if (!ent.isDead) {
							if (MLN.LogTileEntityBuilding >= MLN.DEBUG) {
								MLN.debug(this, "Killing enderman " + ent + " at "
										+ ent.posX + "/" + ent.posY + "/" + ent.posZ);
							}
							ent.setDead();
						}
					}
				}
			}
		}

	}

	private void loadChunks() {
		if ((winfo!=null) && (winfo.width>0)) {
			if (chunkLoader==null) {
				chunkLoader=new BuildingChunkLoader(this);
			}
			if (!chunkLoader.chunksLoaded) {
				chunkLoader.loadChunks();
			}
		}
	}

	public void lockAllBuildingsChests() {

		for (final Point p : buildings) {
			final Building b = mw.getBuilding(p);
			if (b != null) {
				b.lockChests();
			}
		}

		saveNeeded = true;
		saveReason = "Locking chests";
	}

	public void lockChests() {

		chestLocked = true;
		for (final Point p : chests) {
			final TileEntityMillChest chest = p.getMillChest(worldObj);
			if (chest != null) {
				chest.buildingPos=getPos();
			}
		}
	}

	public boolean lockedForPlayer(String playerName) {
		if (!chestLocked)
			return false;

		return !controlledBy(playerName);
	}

	private void merchantCreated() {

		if (MLN.LogMerchant >= MLN.MINOR) {
			MLN.minor(this, "Creating a new merchant");
		}

		merchantRecord = vrecords.lastElement();
		visitorsList.add("panels.startedtrading;"+merchantRecord.getName()+";"+merchantRecord.getNativeOccupationName());

	}

	private void moveMerchant(Building destInn) {
		final HashMap<InvItem, Integer> contents = getChestsContent();


		// moving Inn goods to dest
		for (final InvItem key : contents.keySet()) {
			final int nb = takeGoods(key.id(), key.meta, 9999999);
			destInn.storeGoods(key.id(), key.meta, nb);
			destInn.addToImports(key, nb);
			addToExports(key, nb);
		}
		transferVillager(merchantRecord, destInn, false);

		visitorsList.add("panels.merchantmovedout;"+merchantRecord.getName()+";"+merchantRecord.getNativeOccupationName()
				+";"+destInn.getTownHall().getVillageQualifiedName()+";"+nbNightsMerchant);

		destInn.visitorsList.add("panels.merchantarrived;"+merchantRecord.getName()+";"+merchantRecord.getNativeOccupationName()
				+";"+getTownHall().getVillageQualifiedName());

		if (MLN.LogMerchant >= MLN.MAJOR) {
			MLN.major(
					this,
					"Moved merchant " + merchantRecord + " to "
							+ destInn.getTownHall());
		}

		destInn.merchantRecord = merchantRecord;
		merchantRecord = null;

		nbNightsMerchant = 0;
	}

	public int nbGoodAvailable(int goodId, int meta, boolean forExport) {

		int nb = countGoods(goodId, meta);

		if ((builder != null) && (buildingLocationIP != null)
				&& buildingLocationIP.key.equals(buildingGoal)) {
			nb += builder.countInv(goodId, meta);
		}

		if (nb == 0)
			return 0;

		int reserveAmount = 0;

		final InvItem item = new InvItem(goodId, meta);

		boolean tradedHere=false;

		if ((location.shop!=null) && culture.shopSells.containsKey(location.shop)) {

			for (final Goods g : culture.shopSells.get(location.shop)) {
				if (g.item.matches(item)) {
					tradedHere=true;
				}
			}

		}


		if (isTownhall || tradedHere || forExport) {
			if (meta==-1) {
				for (int i=0;i<16;i++) {
					final InvItem nitem = new InvItem(goodId, i);
					if (culture.goodsByItem.containsKey(nitem)) {
						final Goods good = culture.goodsByItem.get(nitem);
						if (good != null) {
							if (forExport) {
								reserveAmount = good.targetQuantity;
							} else {
								reserveAmount = good.reservedQuantity;
							}
						}
					}
				}
			} else {
				if (culture.goodsByItem.containsKey(item)) {
					final Goods good = culture.goodsByItem.get(item);
					if (good != null) {
						if (forExport) {
							reserveAmount = good.targetQuantity;
						} else {
							reserveAmount = good.reservedQuantity;
						}
					}
				}
			}
		}

		for (final VillagerRecord vr : vrecords) {

			if ((vr.housePos!=null) && vr.housePos.equals(getPos()) && (vr.getType()!=null)) {
				for (final InvItem requiredItem : vr.getType().requiredFoodAndGoods.keySet()) {
					if (item.matches(requiredItem)) {
						reserveAmount+=vr.getType().requiredFoodAndGoods.get(requiredItem);
					}
				}
			}

		}



		if (MLN.LogMerchant>=MLN.DEBUG) {
			MLN.debug(this, "Reserved amount: "+item.getName()+": "+reserveAmount+"/"+nb);
		}

		final BuildingPlan project = this.getCurrentGoalBuildingPlan();

		if (project != null) {
			for (final InvItem key : project.resCost.keySet()) {
				if ((key.id() == goodId) && ((key.meta == meta) || (meta==-1) || (key.meta==-1))) {

					if (MLN.LogMerchant>=MLN.DEBUG) {
						MLN.debug(this, "Needed for project: "+project.resCost.get(key));
					}

					if ((project.resCost.get(key) + reserveAmount) >= nb)
						return 0;
					else
						return (nb - project.resCost.get(key) - reserveAmount);
				}
			}
		}



		if (reserveAmount < nb)
			return (nb - reserveAmount);

		return 0;
	}

	public int nbGoodNeeded(int goodId, int meta) {

		int nb = countGoods(goodId, meta);

		if ((builder != null) && (buildingLocationIP != null)
				&& buildingLocationIP.key.equals(buildingGoal)) {
			nb += builder.countInv(goodId, meta);
		}

		int targetAmount = 0;
		final InvItem item = new InvItem(goodId, meta);

		if (meta==-1) {
			for (int i=0;i<16;i++) {
				if (culture.goodsByItem.containsKey(item)) {
					final Goods good = culture.goodsByItem.get(new InvItem(goodId, i));
					if (good != null) {
						targetAmount += good.targetQuantity;
					}
				}
			}
		} else {
			if (culture.goodsByItem.containsKey(item)) {
				final Goods good = culture.goodsByItem.get(item);
				if (good != null) {
					targetAmount = good.targetQuantity;
				}
			}
		}



		final BuildingPlan project = this.getCurrentGoalBuildingPlan();

		int neededForProject=0;

		if (project != null) {
			for (final InvItem key : project.resCost.keySet()) {
				if ((key.id() == goodId) && ((key.meta == meta) || (meta==-1) || (key.meta==-1))) {
					neededForProject+=project.resCost.get(key);
				}
			}
		}

		if (MLN.LogMerchant>=MLN.DEBUG) {
			MLN.debug(this, "Goods needed: "+item.getName()+": "+targetAmount+"/"+neededForProject+"/"+nb);
		}

		return Math.max((neededForProject + targetAmount) - nb, 0);
	}



	private void readBblocks() {
		final File buildingsDir = MillCommonUtilities.getBuildingsDir(worldObj);
		final File file1 = new File(buildingsDir, getPos().getPathString()
				+ "_bblocks.bin");

		if (file1.exists()) {
			try {
				final FileInputStream fis = new FileInputStream(file1);
				final DataInputStream ds = new DataInputStream(fis);

				final int size=ds.readInt();

				bblocks=new BuildingBlock[size];

				for (int i=0;i<size;i++) {
					final Point p=new Point(ds.readInt(),ds.readShort(),ds.readInt());
					final BuildingBlock b=new BuildingBlock(p,ds.readShort(),ds.readByte(),ds.readByte());
					bblocks[i] = b;
				}

				if (bblocks.length==0) {
					MLN.error(this, "Saved bblocks had zero elements. Rushing construction.");
					try {
						rushBuilding();
					} catch (Exception e) {
						MLN.printException("Exception when trying to rush building:", e);
					}
				}

				ds.close();

			} catch (final Exception e) {
				MLN.printException("Error when reading bblocks: ",e);
				bblocks=null;
			}
		}
	}

	public boolean readFromNBT(NBTTagCompound nbttagcompound) {
		try {

			final String version = nbttagcompound
					.getString("versionCompatibility");

			if (!version.equals(versionCompatibility)) {
				MLN.error(this,
						"Tried to load building with incompatible version: "
								+ version);
				return false;
			}

			if (pos == null) {
				pos = Point.read(nbttagcompound, "pos");
			}

			chestLocked = nbttagcompound.getBoolean("chestLocked");

			location = BuildingLocation.read(nbttagcompound, "buildingLocation","self");

			if (location == null) {
				MLN.error(this, "No location found!");
				return false;
			}

			culture = Culture.getCultureByName(nbttagcompound
					.getString("culture"));

			if (culture == null) {
				MLN.error(
						this,
						"Could not load culture: "
								+ nbttagcompound.getString("culture")+", skipping building.");
				return false;
			}

			if (nbttagcompound.hasKey("isTownhall")) {
				isTownhall = nbttagcompound.getBoolean("isTownhall");
			} else {
				isTownhall = location.key.equals(blTownhall);
			}

			sleepingPos = Point.read(nbttagcompound, "spawnPos");
			sellingPos = Point.read(nbttagcompound, "sellingPos");
			craftingPos = Point.read(nbttagcompound, "craftingPos");
			defendingPos = Point.read(nbttagcompound, "defendingPos");
			shelterPos = Point.read(nbttagcompound, "shelterPos");
			pathStartPos = Point.read(nbttagcompound, "pathStartPos");
			leasurePos = Point.read(nbttagcompound, "leasurePos");
			townHallPos = Point.read(nbttagcompound, "townHallPos");

			thNightActionPerformed=nbttagcompound.getBoolean("nightActionPerformed");
			nightBackgroundActionPerformed=nbttagcompound.getBoolean("nightBackgroundActionPerformed");

			if (sleepingPos == null) {
				sleepingPos=pos.getAbove();
				//MLN.warning(this, "No sleeping pos found, using the chest pos instead.");
			}

			NBTTagList nbttaglist = nbttagcompound.getTagList("chests");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					if (!chests.contains(p)) {
						chests.add(p);
					}
				}
			}

			if (!chests.contains(pos)) {
				chests.insertElementAt(pos, 0);
			}

			nbttaglist = nbttagcompound.getTagList("furnaces");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					furnaces.add(p);
				}
			}

			nbttaglist = nbttagcompound.getTagList("brewingStands");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					brewingStands.add(p);
				}
			}

			nbttaglist = nbttagcompound.getTagList("signs");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					signs.add(p);
				}
			}



			nbttaglist = nbttagcompound.getTagList("netherwartsoils");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					netherwartsoils.add(p);
				}
			}



			nbttaglist = nbttagcompound.getTagList("silkwormblock");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					silkwormblock.add(p);
				}
			}



			nbttaglist = nbttagcompound.getTagList("sugarcanesoils");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					sugarcanesoils.add(p);
				}
			}

			nbttaglist = nbttagcompound.getTagList("fishingspots");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					fishingspots.add(p);
				}
			}

			nbttaglist = nbttagcompound.getTagList("healingspots");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					healingspots.add(p);
				}
			}

			nbttaglist = nbttagcompound.getTagList("stalls");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					stalls.add(p);
				}
			}

			nbttaglist = nbttagcompound.getTagList("woodspawn");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					woodspawn.add(p);
				}
			}

			nbttaglist = nbttagcompound.getTagList("brickspot");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					brickspot.add(p);
				}
			}

			nbttaglist = nbttagcompound.getTagList("spawns");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);

				String spawnType=nbttagcompound1.getString("type");

				//convertion for pre-4.3 tags
				if (spawnType.equals("ml_FarmPig")) {
					spawnType="Pig";
				} else if (spawnType.equals("ml_FarmCow")) {
					spawnType="Cow";
				} else if (spawnType.equals("ml_FarmChicken")) {
					spawnType="Chicken";
				} else if (spawnType.equals("ml_FarmSheep")) {
					spawnType="Sheep";
				}

				spawnTypes.add(spawnType);
				final Vector<Point> v = new Vector<Point>();

				final NBTTagList nbttaglist2 = nbttagcompound1
						.getTagList("points");
				for (int j = 0; j < nbttaglist2.tagCount(); j++) {
					final NBTTagCompound nbttagcompound2 = (NBTTagCompound) nbttaglist2
							.tagAt(j);
					final Point p = Point.read(nbttagcompound2, "pos");
					if (p != null) {
						v.add(p);
						if (MLN.LogHybernation >= MLN.MINOR) {
							MLN.minor(this, "Loaded spawn point: " + p);
						}
					}
				}
				spawns.add(v);
				if (MLN.LogHybernation >= MLN.MINOR) {
					MLN.minor(this, "Loaded " + v.size() + " spawn points for "
							+ spawnTypes.get(i));
				}
			}

			nbAnimalsRespawned=nbttagcompound.getInteger("nbAnimalsRespawned");

			nbttaglist = nbttagcompound.getTagList("mobspawns");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);

				mobSpawnerTypes.add(nbttagcompound1.getString("type"));
				final Vector<Point> v = new Vector<Point>();

				final NBTTagList nbttaglist2 = nbttagcompound1
						.getTagList("points");
				for (int j = 0; j < nbttaglist2.tagCount(); j++) {
					final NBTTagCompound nbttagcompound2 = (NBTTagCompound) nbttaglist2
							.tagAt(j);
					final Point p = Point.read(nbttagcompound2, "pos");
					if (p != null) {
						v.add(p);
						if (MLN.LogHybernation >= MLN.MINOR) {
							MLN.minor(this, "Loaded spawn point: " + p);
						}
					}
				}
				mobSpawners.add(v);
				if (MLN.LogHybernation >= MLN.MINOR) {
					MLN.minor(this, "Loaded " + v.size()
							+ " mob spawn points for " + spawnTypes.get(i));
				}
			}

			nbttaglist = nbttagcompound.getTagList("sources");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);

				sourceTypes.add(nbttagcompound1.getInteger("type"));
				final Vector<Point> v = new Vector<Point>();

				final NBTTagList nbttaglist2 = nbttagcompound1
						.getTagList("points");
				for (int j = 0; j < nbttaglist2.tagCount(); j++) {
					final NBTTagCompound nbttagcompound2 = (NBTTagCompound) nbttaglist2
							.tagAt(j);
					final Point p = Point.read(nbttagcompound2, "pos");
					if (p != null) {
						v.add(p);
						if (MLN.LogHybernation >= MLN.DEBUG) {
							MLN.debug(this, "Loaded source point: " + p);
						}
					}
				}
				sources.add(v);
				if (MLN.LogHybernation >= MLN.MAJOR) {
					MLN.debug(this, "Loaded " + v.size()
							+ " sources points for "
							+ Item.itemsList[sourceTypes.get(i)].getUnlocalizedName());
				}
			}

			nbttaglist = nbttagcompound.getTagList("villagersrecords");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final VillagerRecord vr = VillagerRecord.read(mw,culture,townHallPos,
						nbttagcompound1, "vr");

				if (vr == null) {
					MLN.error(this, "Couldn't load VR record.");
				} else {
					addOrReplaceRecord(vr);
					if (MLN.LogHybernation >= MLN.MINOR) {
						MLN.minor(this, "Loaded VR: " + vr);
					}
				}
			}

			nbttaglist = nbttagcompound.getTagList("visitorsList");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				visitorsList.add(nbttagcompound1.getString("visitor"));
			}

			nbttaglist = nbttagcompound.getTagList("subBuildings");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);
				final Point p = Point.read(nbttagcompound1, "pos");
				if (p != null) {
					subBuildings.add(p);
				}
			}

			readLegacySoilsFromNBT(nbttagcompound);

			nbttaglist = nbttagcompound.getTagList("genericsoils");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);

				final String type=nbttagcompound1.getString("type");

				final NBTTagList nbttaglist2 = nbttagcompound1
						.getTagList("points");
				for (int j = 0; j < nbttaglist2.tagCount(); j++) {
					final NBTTagCompound nbttagcompound2 = (NBTTagCompound) nbttaglist2
							.tagAt(j);
					final Point p = Point.read(nbttagcompound2, "pos");
					if (p != null) {
						addSoilPoint(type,p);
					}
				}
			}


			if (location.tags.contains(tagPujas)) {
				pujas=new Puja(this,nbttagcompound.getCompoundTag("pujas"));
				if (MLN.LogPujas>=MLN.MINOR) {
					MLN.minor(this, "read pujas object");
				}
			}

			lastGoodsRefresh=nbttagcompound.getLong("lastGoodsRefresh");

			if (location.tags.contains(tagInn) && !isTownhall) {
				isInn = true;
				readInn(nbttagcompound);
			}

			if (isInn && (vrecords.size() > 0)) {
				merchantRecord = vrecords.get(0);
			}

			if ((location.tags.contains(tagMarket)) && !isTownhall) {
				isMarket = true;
			}

			if (isTownhall) {
				if (MLN.LogHybernation >= MLN.MAJOR) {
					MLN.major(this, "Loading Townhall data.");
				}
				readTownHall(nbttagcompound);
			}

			for (final Point p : chests) {
				if (worldObj.blockExists(p.getiX(),p.getiY(), p.getiZ()) && (p.getMillChest(worldObj)!=null)) {
					p.getMillChest(worldObj).buildingPos=getPos();
				}
			}

			if (MLN.LogTileEntityBuilding >= MLN.DEBUG) {
				MLN.debug(this, "Loading building. Type: " + location
						+ ", pos: " + getPos());
			}

			return true;

		} catch (final Exception e) {
			Mill.proxy.sendChatAdmin("Error when trying to load building. Check millenaire.log.");
			MLN.error(this, "Error when trying to load building of type: "
					+ location);
			MLN.printException(e);

			return false;
		}
	}


	public void readInn(NBTTagCompound nbttagcompound)
			throws MillenaireException {

		NBTTagList nbttaglist = nbttagcompound.getTagList("importedGoods");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound tag = (NBTTagCompound) nbttaglist.tagAt(i);
			final InvItem good = new InvItem(tag.getInteger("itemid"),
					tag.getInteger("itemmeta"));
			imported.put(good, tag.getInteger("quantity"));
		}

		nbttaglist = nbttagcompound.getTagList("exportedGoods");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound tag = (NBTTagCompound) nbttaglist.tagAt(i);
			final InvItem good = new InvItem(tag.getInteger("itemid"),
					tag.getInteger("itemmeta"));
			exported.put(good, tag.getInteger("quantity"));
		}
	}

	//for old pre-4.3 soil handling
	private void readLegacySoilsFromNBT(NBTTagCompound nbttagcompound) {
		NBTTagList nbttaglist = nbttagcompound.getTagList("soils");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			final Point p = Point.read(nbttagcompound1, "pos");
			if (p != null) {
				addSoilPoint(Mill.CROP_WHEAT,p);
			}
		}

		nbttaglist = nbttagcompound.getTagList("ricesoils");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			final Point p = Point.read(nbttagcompound1, "pos");
			if (p != null) {
				addSoilPoint(Mill.CROP_RICE,p);
			}
		}

		nbttaglist = nbttagcompound.getTagList("turmericsoils");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			final Point p = Point.read(nbttagcompound1, "pos");
			if (p != null) {
				addSoilPoint(Mill.CROP_TURMERIC,p);
			}
		}

		nbttaglist = nbttagcompound.getTagList("maizesoils");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			final Point p = Point.read(nbttagcompound1, "pos");
			if (p != null) {
				addSoilPoint(Mill.CROP_MAIZE,p);
			}
		}

		nbttaglist = nbttagcompound.getTagList("vinesoils");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			final Point p = Point.read(nbttagcompound1, "pos");
			if (p != null) {
				addSoilPoint(Mill.CROP_VINE,p);
			}
		}
	}

	private void readPaths() {
		final File buildingsDir = MillCommonUtilities.getBuildingsDir(worldObj);
		File file1 = new File(buildingsDir, getPos().getPathString()
				+ "_paths.bin");

		if (file1.exists()) {
			try {
				final FileInputStream fis = new FileInputStream(file1);
				final DataInputStream ds = new DataInputStream(fis);

				final int size=ds.readInt();

				pathsToBuild=new Vector<Vector<BuildingBlock>>();

				for (int i=0;i<size;i++) {

					final Vector<BuildingBlock> path=new Vector<BuildingBlock>();

					final int sizePath=ds.readInt();

					for (int j=0;j<sizePath;j++) {
						final Point p=new Point(ds.readInt(),ds.readShort(),ds.readInt());
						final BuildingBlock b=new BuildingBlock(p,ds.readShort(),ds.readByte(),ds.readByte());
						path.add(b);
					}
					pathsToBuild.add(path);
				}

				ds.close();

			} catch (final Exception e) {
				MLN.printException("Error when reading pathsToBuild: ",e);
				bblocks=null;
			}
		}

		file1 = new File(buildingsDir, getPos().getPathString()
				+ "_pathstoclear.bin");

		if (file1.exists()) {
			try {
				final FileInputStream fis = new FileInputStream(file1);
				final DataInputStream ds = new DataInputStream(fis);

				final int size=ds.readInt();

				oldPathPointsToClear=new Vector<Point>();

				for (int i=0;i<size;i++) {
					final Point p=new Point(ds.readInt(),ds.readShort(),ds.readInt());
					oldPathPointsToClear.add(p);
				}

				ds.close();

			} catch (final Exception e) {
				MLN.printException("Error when reading oldPathPointsToClear: ",e);
				bblocks=null;
			}
		}


	}


	public void readTownHall(NBTTagCompound nbttagcompound) {

		name = nbttagcompound.getString("name");
		qualifier = nbttagcompound.getString("qualifier");
		final String vtype = nbttagcompound.getString("villageType");

		if (vtype.length() == 0) {
			villageType = culture.getRandomVillage();
		} else if (culture.getVillageType(vtype)!=null) {
			villageType = culture.getVillageType(vtype);
		} else if (culture.getLoneBuildingType(vtype)!=null) {
			villageType = culture.getLoneBuildingType(vtype);
		} else {
			villageType = culture.getRandomVillage();
		}

		controlledBy=nbttagcompound.getString("controlledBy");

		// legacy, to be removed later
		NBTTagList nbttaglist = nbttagcompound.getTagList("houses");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			final Point p = Point.read(nbttagcompound1, "pos");
			if (p != null) {
				buildings.add(p);
			}
		}

		nbttaglist = nbttagcompound.getTagList("buildings");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			final Point p = Point.read(nbttagcompound1, "pos");
			if (p != null) {
				buildings.add(p);
			}
		}

		initialiseBuildingProjects();

		nbttaglist = nbttagcompound.getTagList("locations");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			final BuildingLocation location = BuildingLocation.read(
					nbttagcompound1, "location", "locations");
			if (location==null) {
				MLN.error(this, "Could not load building location. Skipping.");
			} else {
				fillinBuildingLocation(location);
			}
		}

		for (int i=buildings.size()-1;i>=0;i--) {
			boolean foundLocation=false;

			for (final BuildingLocation l : this.getLocations()) {
				if (buildings.get(i).equals(l.chestPos)) {
					foundLocation=true;
				}
			}
			if (!foundLocation) {
				MLN.error(this, "Deleting building as could not find the location for it at: "+buildings.get(i));
				buildings.remove(i);
			}

		}


		//All projects in a controlled village must have a defined location
		//If a starting building was removed by the player, will be filled up again by initialiseBuildingProjects()
		//So must be removed again
		if (villageType.playerControlled) {
			for (final Vector<BuildingProject> level : buildingProjects) {
				final Vector<BuildingProject> toDelete=new Vector<BuildingProject>();

				for (final BuildingProject project : level) {
					if (project.location==null) {
						toDelete.add(project);
					}
				}

				for (final BuildingProject project : toDelete) {
					level.remove(project);
				}
			}
		}

		buildingGoal = nbttagcompound.getString("buildingGoal");
		if (culture.getBuildingPlanSet(buildingGoal)==null) {
			buildingGoal = null;
			buildingGoalLevel = 0;
			buildingGoalVariation = 0;
			if (MLN.LogHybernation >= MLN.MAJOR) {
				MLN.major(this, "No goal found: " + buildingGoal);
			}
		} else {
			buildingGoalLevel = nbttagcompound.getInteger("buildingGoalLevel");
			buildingGoalVariation = nbttagcompound
					.getInteger("buildingGoalVariation");
			if (MLN.LogHybernation >= MLN.MAJOR) {
				MLN.major(this, "Reading building goal: " + buildingGoal);
			}
		}

		buildingGoalLocation = BuildingLocation.read(nbttagcompound,
				"buildingGoalLocation","buildingGoalLocation");
		if (buildingGoalLocation != null) {
			if (MLN.LogHybernation >= MLN.MAJOR) {
				MLN.major(this, "Loaded buildingGoalLocation: "
						+ buildingGoalLocation);
			}
		}
		buildingGoalIssue = nbttagcompound.getString("buildingGoalIssue");

		buildingLocationIP = BuildingLocation.read(nbttagcompound,
				"buildingLocationIP","buildingLocationIP");

		if (buildingLocationIP != null) {

			if (culture.getBuildingPlanSet(buildingLocationIP.key)==null) {
				buildingLocationIP = null;
			} else {
				final BuildingPlanSet set = culture.getBuildingPlanSet(buildingLocationIP.key);
				if (buildingLocationIP.level >= set.plans
						.get(buildingLocationIP.getVariation()).length) {
					buildingLocationIP = null;
				}
			}



			readBblocks();
			bblocksPos=nbttagcompound.getInteger("bblocksPos");
		}

		nbttaglist = nbttagcompound.getTagList("buildingsBought");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			buildingsBought.add(nbttagcompound1.getString("key"));
		}

		parentVillage = Point.read(nbttagcompound, "parentVillage");

		if (nbttagcompound.hasKey("relations")) {
			nbttaglist = nbttagcompound.getTagList("relations");
			for (int i = 0; i < nbttaglist.tagCount(); i++) {
				final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
						.tagAt(i);

				relations.put(Point.read(nbttagcompound1, "pos"), nbttagcompound1.getInteger("value"));
			}
		}

		updateRaidPerformed=nbttagcompound.getBoolean("updateRaidPerformed");
		nightBackgroundActionPerformed=nbttagcompound.getBoolean("nightBackgroundActionPerformed");
		thNightActionPerformed=nbttagcompound.getBoolean("nightActionPerformed");

		raidTarget=Point.read(nbttagcompound, "raidTarget");
		raidPlanningStart=nbttagcompound.getLong("raidPlanningStart");
		raidStart=nbttagcompound.getLong("raidStart");
		underAttack=nbttagcompound.getBoolean("underAttack");

		nbttaglist = nbttagcompound.getTagList("raidsPerformed");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			raidsPerformed.add(nbttagcompound1.getString("raid"));
		}

		nbttaglist = nbttagcompound.getTagList("raidsTaken");
		for (int i = 0; i < nbttaglist.tagCount(); i++) {
			final NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist
					.tagAt(i);
			raidsSuffered.add(nbttagcompound1.getString("raid"));
		}


		pathsToBuildIndex = nbttagcompound.getInteger("pathsToBuildIndex");
		pathsToBuildPathIndex = nbttagcompound.getInteger("pathsToBuildPathIndex");
		oldPathPointsToClearIndex = nbttagcompound.getInteger("oldPathPointsToClearIndex");

		readPaths();

	}

	public boolean rebuildPathing(boolean sync) throws MillenaireException {

		if (MLN.jpsPathing)
			return false;

		if (sync) {
			final AStarPathing temp = new AStarPathing();

			if (temp.createConnectionsTable(winfo,sleepingPos)) {
				pathing = temp;
				lastPathingUpdate = System.currentTimeMillis();
				return true;
			} else {
				pathing = null;
				lastPathingUpdate = System.currentTimeMillis();
				return false;
			}
		} else if (!rebuildingPathing) {
			PathingThread thread;
			try {
				rebuildingPathing = true;
				thread = new PathingThread(winfo.clone());
				thread.setPriority(Thread.MIN_PRIORITY);
				if (MLN.LogPathing >= MLN.MAJOR) {
					MLN.major(this, "Thread starting.");
				}
				thread.start();
				if (MLN.LogPathing >= MLN.MAJOR) {
					MLN.major(this, "Thread started.");
				}
			} catch (final CloneNotSupportedException e) {
				MLN.printException(e);
			}
			return true;
		}
		return true;
	}


	public void rebuildSurfacePathing() {
		if (binaryPathing == null) {
			binaryPathing = new PathingBinary(worldObj);
		}

		binaryPathing.updatePathing(getPos(), villageType.radius + 20, 20);

		lastPathingUpdate = System.currentTimeMillis();
	}

	public void recalculatePaths(boolean autobuild) {

		if (!MLN.BuildVillagePaths)
			return;

		int nbPaths=0;

		for (final Building b : getBuildings()) {
			if (b!=this && (b.location!=null) && (b.location.getPlan()!=null) && !b.location.getPlan().isSubBuilding() && (b.getPathStartPos()!=null)) {
				nbPaths++;
			}
		}

		final PathCreatorInfo info=new PathCreatorInfo(nbPaths);
		this.autobuildPaths=autobuild;

		final Point start=getPathStartPos();

		if (MLN.LogVillagePaths>=MLN.MINOR) {
			MLN.minor(this, "Launching path rebuild, expected paths number: "+nbPaths);
		}

		for (final Building b : getBuildings()) {
			if ((b!=this) && (b.location!=null) && (b.location.getPlan()!=null) && !b.location.getPlan().isSubBuilding() && (b.getPathStartPos()!=null)) {

				InvItem pathMaterial=villageType.pathMaterial.firstElement();

				if (b.location.getPlan().pathLevel<villageType.pathMaterial.size()) {
					pathMaterial=villageType.pathMaterial.get(b.location.getPlan().pathLevel);
				}

				final PathCreator pathCreator=new PathCreator(info,pathMaterial,b.location.getPlan().pathWidth,b);

				final AStarPathPlanner jpsPathPlanner=new AStarPathPlanner(worldObj,pathCreator);

				jpsPathPlanner.getPath(start.getiX(), start.getiY(), start.getiZ(),
						b.getPathStartPos().getiX(), b.getPathStartPos().getiY(), b.getPathStartPos().getiZ(), PATH_BUILDER_JPS_CONFIG);
			}
		}
	}

	private void refreshGoods() {

		if ((location == null) || (location.getPlan()==null) || (location.getPlan().startingGoods.size()==0))
			return;

		if (worldObj.isDaytime()) {
			refreshGoodsNightActionPerformed=false;
		} else {
			if (!refreshGoodsNightActionPerformed) {

				long interval;

				if (chestLocked) {

					interval=20;
				} else {

					interval=20*5;
				}

				if (((lastGoodsRefresh+(interval*24000))<worldObj.getWorldTime()) && chestLocked) {
					fillStartingGoods();
					lastGoodsRefresh=worldObj.getWorldTime();
				}

				refreshGoodsNightActionPerformed=true;
			}
		}
	}


	public void registerBuildingEntity(Building buildingEntity)
			throws MillenaireException {

		if (buildingEntity != this) {
			for (final MillVillager v : buildingEntity.villagers) {

				addOrReplaceVillager(v);
				if (v.getRecord() != null) {
					addOrReplaceRecord(v.getRecord());
				} else {
					addOrReplaceRecord(new VillagerRecord(mw,v));
				}

			}
		}
		buildings.add(buildingEntity.getPos());
		saveNeeded = true;
		saveReason = "Registering building";
	}

	public void registerBuildingLocation(BuildingLocation location) {

		boolean registered = false;
		int nbProjects = 0;

		// Projects
		for (int i = 0; (i < buildingProjects.size()) && !registered; i++) {
			for (int j = 0; (j < buildingProjects.get(i).size()) && !registered; j++) {
				final BuildingProject project = buildingProjects.get(i)
						.get(j);
				if (location.level == 0) {
					if (project.key.equals(location.key)
							&& ((project.location == null) || ((project.location.level < 0) && project.location
									.isSameLocation(location)))) {
						if (project.location != null) {
							location.upgradesAllowed = project.location.upgradesAllowed;
						}
						project.location = location;
						registered = true;
						if (MLN.LogBuildingPlan >= MLN.MAJOR) {
							MLN.major(this, "Updated building project: "
									+ project + " with initial location.");
						}
					}
				} else {
					if (location.isSameLocation(project.location)) {
						if (MLN.LogBuildingPlan >= MLN.MAJOR) {
							MLN.major(this, "Updated building project: "
									+ project + " from level "
									+ project.location.level + " to "
									+ location.level);
						}

						location.upgradesAllowed = project.location.upgradesAllowed;
						project.location = location;
						registered = true;
					}
				}
				nbProjects++;
			}

		}

		if (registered) {
			if (MLN.LogBuildingPlan >= MLN.MAJOR) {
				MLN.major(this, "Registered building location: " + location);
			}
		} else {
			MLN.error(this, "Could not register building location: " + location
					+ " amoung " + nbProjects + " projects.");
		}

		// Buildings
		if (getCurrentBuildingPlan() != null) {
			for (final Point p : buildings) {
				final Building building = mw.getBuilding(p);
				if (MLN.LogBuildingPlan >= MLN.MAJOR) {
					MLN.major(this, "Testing building: " + building);
				}
				if ((building != null) && (building.location != null)
						&& building.location.isSameLocation(location)) {
					building.location = location;
					getCurrentBuildingPlan().referenceBuildingPoints(worldObj,
							building, location);
					if (MLN.LogBuildingPlan >= MLN.MAJOR) {
						MLN.major(this,
								"Updated building location for building: "
										+ building + " now at upgrade: "
										+ location.level);
					}
				}
			}
		}

		// Adding sub-building projects
		for (final String s : location.subBuildings) {
			boolean found = false;
			Vector<BuildingProject> parentProjectLevel = null;
			int parentPos = 0;

			for (final Vector<BuildingProject> projects : buildingProjects) {
				int pos = 0;
				for (final BuildingProject project : projects) {
					if (project.location != null) {
						if (project.location.isLocationSamePlace(location)
								&& project.key.equals(s)) {
							found = true;
						} else if (project.location.isSameLocation(location)) {
							parentProjectLevel = projects;
							parentPos = pos;
						}
					}
					pos++;
				}
			}
			if (!found && (parentProjectLevel != null)) {

				if (culture.getBuildingPlanSet(s)==null) {
					MLN.error(this,
							"Could not find plan for finished building: "+s);
					return;
				}

				final BuildingProject project = new BuildingProject(
						culture.getBuildingPlanSet(s));
				project.location = location.createLocationForSubBuilding(s);
				parentProjectLevel.insertElementAt(project, parentPos + 1);

			}
		}

		saveNeeded = true;
		saveReason = "Registering location";
	}

	public void registerVillager(MillVillager villager)
			throws MillenaireException {

		if (MLN.LogGeneralAI >= MLN.DEBUG) {
			MLN.debug(this, "Registering villager " + villager);
		}

		addOrReplaceVillager(villager);

		int nbFound = 0;
		VillagerRecord vrfound=null;

		for (int i = 0; i < vrecords.size(); i++) {
			final VillagerRecord vr = vrecords.get(i);
			if (vr.id == villager.villager_id) {
				nbFound++;
				vrfound=vr;
			}
		}

		if (vrfound!=null) {
			vrfound.updateRecord(villager);
		}

		if (nbFound == 0) {
			final VillagerRecord vr = villager.getRecord();// maybe the other
			// building has it

			if (vr != null) {
				if (MLN.LogGeneralAI >= MLN.MAJOR) {
					MLN.major(this, "Adding record " + vr);
				}
				addOrReplaceRecord(vr);
			} else {
				if (MLN.LogGeneralAI >= MLN.MAJOR) {
					MLN.major(this, "Adding new record "
							+ new VillagerRecord(mw,villager));
				}
				addOrReplaceRecord(new VillagerRecord(mw,villager));
			}
			saveNeeded = true;
			saveReason = "Registering villager";
		}

	}

	public boolean removeVillagerRecord(long vid) {

		for (final VillagerRecord vr : vrecords) {
			if (vr.id == vid) {
				vrecords.remove(vr);
				return true;
			}
		}
		return false;
	}

	private void repairVillagerList() throws MillenaireException {

		@SuppressWarnings("rawtypes")
		final
		List entities=MillCommonUtilities.getEntitiesWithinAABB(worldObj, MillVillager.class, getPos(), villageType.radius+20, 50);

		for (final Object o : entities) {
			final MillVillager v=(MillVillager)o;

			if (v.getTownHall()==this) {
				if (!villagers.contains(v) && (v.vtype!=null)) {
					villagers.add(v);
				}
			}
		}

		for (int i = villagers.size() - 1; i > -1; i--) {
			if (villagers.get(i).isDead) {//actually, inactive not dead
				villagers.remove(i);
			}
		}


		// Condition to do respawning of killed villagers:
		// it's the middle of the night
		final int time = (int) (worldObj.getWorldTime() % 24000L);

		boolean resurect = ((time >= 13000) && (time < 13100));


		if (resurect) {
			resurect=true;
		}

		for (final VillagerRecord vr : vrecords) {
			final Vector<MillVillager> found = new Vector<MillVillager>();
			for (final MillVillager v : villagers) {
				if (vr.matches(v)) {
					found.add(v);
				}
			}

			if (found.size() == 0) {
				boolean respawn=false;

				if (!vr.flawedRecord) {
					if (vr.raidingVillage) {
						//Villagers raiding this village are respawn but never resurected. Respawn is delayed by INVADER_SPAWNING_DELAY
						if (!vr.killed && (worldObj.getWorldTime()>(vr.raiderSpawn+INVADER_SPAWNING_DELAY))) {
							respawn=true;
						}
					} else if (!vr.awayraiding && !vr.awayhired) {
						//Villagers raiding an other village never get respawn at all
						//Killed villagers have to wait for the night. Despawneds one are recreated ASAP.
						if (!vr.killed || resurect) {
							respawn=true;
						}
					}
				}

				if (respawn) {
					if (MLN.LogGeneralAI >= MLN.MAJOR) {
						MLN.major(this, "Recreating missing villager from record " + vr+". Killed: "+vr.killed);
					}
					if (mw.getBuilding(vr.housePos) == null) {
						MLN.error(this,
								"Error when trying to recreate a villager from record "
										+ vr + ": couldn't load house at "
										+ vr.housePos + ".");
					} else {

						Point villagerPos;

						//raiders respawn on the edge of the village
						if (vr.raidingVillage && (vr.originalVillagePos!=null)) {
							villagerPos=findAttackerSpawnPoint(vr.originalVillagePos);
						} else {
							if (underAttack) {
								if (vr.getType().helpInAttacks) {//fighter
									villagerPos=getDefendingPos();
								} else {
									villagerPos=getShelterPos();
								}
							} else {//normal case
								villagerPos=mw.getBuilding(vr.housePos).getSleepingPos();
							}

						}

						final MillVillager villager = MillVillager.createVillager(
								vr.culture, vr.type, vr.gender, worldObj,
								villagerPos, vr.housePos, getPos(),true,
								vr.firstName, vr.familyName);

						if (villager == null) {
							MLN.error(this, "Could not recreate villager " + vr
									+ " of type " + vr.type);
						} else {

							// Reloading type from villager in case it was changed
							// by createVillager
							// (such as when converting an old static villager to a
							// dynamic one)
							vr.nameKey = villager.getNameKey();


							if (!vr.killed) {//despawn, so gets inventory back

								if (MLN.LogGeneralAI >= MLN.MAJOR) {
									MLN.major(this, "Giving the villager back " + vr.inventory.size()+" item types.");
								}

								for (final InvItem iv : vr.inventory.keySet()) {
									villager.addToInv(iv, vr.inventory.get(iv));
								}

							}

							vr.killed=false;

							if (villager.getHouse() != null) {
								villager.setTexture(vr.texture);
								villager.villager_id = vr.id;
								villager.size = vr.villagerSize;
								villager.isRaider = vr.raidingVillage;

								if (!villager.isTextureValid(villager.getTexture())) {
									villager.setTexture(villager.getNewTexture());
								}

								if (villager.isChild()) {
									villager.adjustSize();
								}


								final boolean spawned=worldObj.spawnEntityInWorld(villager);

								if (spawned) {
									registerVillager(villager);
									villager.getHouse().registerVillager(villager);
									villager.registerInGlobalList();
								}

							}
						}
					}
				}
			} else if (found.size() > 1) {
				if (MLN.LogGeneralAI >= MLN.MAJOR) {
					MLN.major(this, "Found " + found.size() + " villagers for record " + vr + ", killing the extras.");
				}

				for (int i = found.size() - 1; i > 0; i--) {
					final MillVillager v = found.get(i);
					villagers.remove(i);
					v.getHouse().villagers.remove(v);
					v.despawnVillager();
				}
			} else if (found.size() == 1) {
				if ((vr.housePos == null) || (vr.texture == null)
						|| (vr.nameKey == "") || vr.nameKey.equals("villager")) {
					MLN.major(this,
							"Updating record for villager: " + found.get(0));
					vr.updateRecord(found.get(0));
					vr.flawedRecord = false;
				}
			}
		}
	}

	private void repairVillagerListClient() {
		for (int i = villagers.size() - 1; i > -1; i--) {
			if (villagers.get(i).isDead) {
				if (MLN.LogGeneralAI >= MLN.MAJOR) {
					MLN.major(this,
							"Client detected a dead villager in the list, removing it: " + villagers.get(i));
				}
				villagers.remove(i);
			}
		}

		for (final VillagerRecord vr : vrecords) {
			final Vector<MillVillager> found = new Vector<MillVillager>();
			for (final MillVillager v : villagers) {
				if (vr.matches(v)) {
					found.add(v);
				}
			}

			if (found.size() > 1) {
				if (MLN.LogGeneralAI >= MLN.MAJOR) {
					MLN.major(this, "Client found " + found.size() + " villagers for record " + vr + ", killing the extras.");
				}

				long mostrecent=0;
				int mostrecentId=0;

				for (int i = 0; i<found.size(); i++) {

					if (found.get(i).client_lastupdated>=mostrecent) {
						mostrecent=found.get(i).client_lastupdated;
						mostrecentId=i;
					}
				}

				for (int i = found.size(); i > 0; i--) {
					final MillVillager v = found.get(i);

					if (i!=mostrecentId) {//don't remove most recent copy of the villager
						villagers.remove(i);
						v.getHouse().villagers.remove(v);
						v.despawnVillager();
					}

				}
			} else if (found.size() == 1) {
				if ((vr.housePos == null) || (vr.texture == null)
						|| (vr.nameKey == "") || vr.nameKey.equals("villager")) {
					MLN.major(this,
							"Updating record for villager: " + found.get(0));
					vr.updateRecord(found.get(0));
					vr.flawedRecord = false;
				}
			}
		}
	}

	public void requestSave(String reason) {
		saveNeeded=true;
		saveReason=reason;
	}

	public void rushBuilding() throws MillenaireException {

		if (buildingLocationIP != null) {
			final BuildingPlan plan = getCurrentBuildingPlan();

			Vector<LocationBuildingPair> lbps;

			if (buildingLocationIP.isSameLocation(location)) {
				lbps = plan.build(mw, villageType, buildingLocationIP,
						false, true, pos, false,null,true);
			} else {
				lbps = plan.build(mw, villageType, buildingLocationIP,
						false, false, pos, false,null,true);
			}

			for (final LocationBuildingPair b : lbps) {
				registerBuildingEntity(b.building);
			}

			setBblocks(null);
		}
		completeConstruction();
	}

	public void saveTownHall(String reason) {

		if (!worldObj.isRemote) {
			if (saveWorker==null) {
				saveWorker=new SaveWorker(reason);
				saveWorker.start();
			}
		}
	}


	public void sendBuildingPacket(EntityPlayer player,boolean sendChest) {

		if (sendChest) {
			for (final Point p :chests) {
				final TileEntityMillChest chest=p.getMillChest(worldObj);

				if (chest!=null) {
					chest.buildingPos=getPos();
					chest.sendUpdatePacket(player);
				}
			}
		}

		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final DataOutputStream data = new DataOutputStream(bytes);

		try {

			data.write(ServerReceiver.PACKET_BUILDING);

			StreamReadWrite.writeNullablePoint(getPos(), data);
			data.writeBoolean(isTownhall);
			data.writeBoolean(chestLocked);
			StreamReadWrite.writeNullableString(controlledBy, data);
			StreamReadWrite.writeNullableString(controlledByName, data);
			StreamReadWrite.writeNullablePoint(townHallPos, data);
			StreamReadWrite.writeNullableString(culture.key, data);
			String vtype=null;
			if (villageType!=null) {
				vtype=villageType.key;
			}
			StreamReadWrite.writeNullableString(vtype, data);
			StreamReadWrite.writeNullableBuildingLocation(location, data);

			StreamReadWrite.writeNullableString(buildingGoal, data);
			StreamReadWrite.writeNullableString(buildingGoalIssue, data);
			data.writeInt(buildingGoalLevel);
			data.writeInt(buildingGoalVariation);
			StreamReadWrite.writeNullableBuildingLocation(this.buildingGoalLocation, data);
			StreamReadWrite.writeNullableBuildingLocation(this.buildingLocationIP, data);
			StreamReadWrite.writeProjectVectorVector(buildingProjects, data);


			StreamReadWrite.writePointVector(chests, data);
			StreamReadWrite.writePointVector(furnaces, data);
			StreamReadWrite.writePointVector(signs, data);
			StreamReadWrite.writePointVector(buildings, data);
			StreamReadWrite.writeStringVector(buildingsBought, data);

			StreamReadWrite.writePointIntegerMap(relations, data);
			StreamReadWrite.writeStringVector(raidsPerformed, data);
			StreamReadWrite.writeStringVector(raidsSuffered, data);

			StreamReadWrite.writeVillagerRecordVector(vrecords, data);

			StreamReadWrite.writeNullablePuja(pujas, data);

			StreamReadWrite.writeStringVector(visitorsList, data);
			StreamReadWrite.writeInventory(imported, data);
			StreamReadWrite.writeInventory(exported, data);

			StreamReadWrite.writeNullableString(name, data);
			StreamReadWrite.writeNullableString(qualifier, data);

		} catch (final IOException e) {
			MLN.printException(this+": Error in sendUpdatePacket", e);
		}

		final Packet250CustomPayload packet = new Packet250CustomPayload();
		packet.channel = ServerReceiver.PACKET_CHANNEL;
		packet.data = bytes.toByteArray();
		packet.length = packet.data.length;

		mw.getProfile(player.username).buildingsSent.put(pos, mw.world.getWorldTime());

		ServerSender.sendPacketToPlayer(packet,player.username);
	}

	private void sendInitialBuildingPackets() {
		for (final EntityPlayer player : MillCommonUtilities.getServerPlayers(mw.world)) {
			if (pos.distanceToSquared(player)<(16*16)) {
				final UserProfile profile=MillCommonUtilities.getServerProfile(mw.world,player.username);

				if (!profile.buildingsSent.containsKey(pos)) {
					this.sendBuildingPacket(player, false);
				}
			}
		}
	}

	public void sendMapInfo(EntityPlayer player) {
		if (winfo!=null) {
			final MillMapInfo minfo=new MillMapInfo(this,winfo);
			minfo.sendMapInfoPacket(player);
		}
	}

	public void setBblocks(BuildingBlock[] bblocks) {
		this.bblocks = bblocks;
		bblocksPos = 0;
		bblocksChanged=true;
	}

	public void setCraftingPos(Point p) {
		craftingPos = p;
	}



	public void setDefendingPos(Point p) {
		defendingPos = p;
	}

	public void setGoods(int item, int newVal) {
		setGoods(item, 0, newVal);
	}

	public void setGoods(int item, int meta, int newVal) {
		final int nb = countGoods(item, meta);

		if (nb < newVal) {
			storeGoods(item, meta, newVal - nb);
		} else {
			takeGoods(item, meta, nb - newVal);
		}
	}

	public void setPathStartPos(Point p) {
		pathStartPos = p;
	}

	public void setLeasurePos(Point p) {
		leasurePos = p;
	}

	public void setSellingPos(Point p) {
		sellingPos = p;
	}

	public void setShelterPos(Point p) {
		shelterPos = p;
	}

	public void setSleepingPos(Point p) {
		sleepingPos = p;
	}

	private void startRaid() {
		final Building distantVillage=mw.getBuilding(raidTarget);

		if ((relations.get(raidTarget)!=null) && (relations.get(raidTarget)>RELATION_OPENCONFLICT)) {//abort raid
			cancelRaid();
		}

		if (distantVillage!=null) {

			raidStart=worldObj.getWorldTime();

			int nbRaider=0;

			for (final VillagerRecord vr : vrecords) {
				if (vr.getType().isRaider && !vr.killed && !vr.raidingVillage && !vr.awayraiding  && !vr.awayhired) {

					if (MLN.LogDiplomacy>=MLN.MINOR) {
						MLN.minor(this, "Need to transfer raider; "+vr);
					}

					vr.getHouse().transferVillager(vr, distantVillage, true);
					nbRaider++;
				}
			}

			if (nbRaider>0) {
				ServerSender.sendTranslatedSentenceInRange(worldObj, getPos(), MLN.BackgroundRadius,MLN.DARKRED,"raid.started",getVillageQualifiedName(),distantVillage.getVillageQualifiedName(),""+nbRaider);
				distantVillage.cancelRaid();
				distantVillage.underAttack=true;
			} else {
				cancelRaid();
			}
		} else {
			cancelRaid();
		}
	}

	public int storeGoods(int item, int toPut) {
		return storeGoods(item, 0, toPut);
	}

	public int storeGoods(int item, int meta, int toPut) {

		int stored = 0;

		int i = 0;
		while ((stored < toPut) && (i < chests.size())) {

			final TileEntityChest chest = chests.get(i).getMillChest(worldObj);

			if (chest != null) {

				stored += MillCommonUtilities.putItemsInChest(chest, item, meta, toPut
						- stored);
			}

			i++;
		}

		return stored;
	}

	public int storeGoods(InvItem item, int toPut) {
		return storeGoods(item.id(), item.meta, toPut);
	}

	private void swapMerchants(Building destInn) {

		final HashMap<InvItem, Integer> contents = getChestsContent();
		final HashMap<InvItem, Integer> destContents = destInn
				.getChestsContent();

		// moving Inn goods to dest
		for (final InvItem key : contents.keySet()) {
			final int nb = takeGoods(key.id(), key.meta, contents.get(key));
			destInn.storeGoods(key.id(), key.meta, nb);
			destInn.addToImports(key, nb);
			addToExports(key, nb);
		}

		// moving dest Inn goods here
		for (final InvItem key : destContents.keySet()) {
			final int nb = destInn.takeGoods(key.id(), key.meta,
					destContents.get(key));
			storeGoods(key.id(), key.meta, nb);
			destInn.addToExports(key, nb);
			addToImports(key, nb);
		}

		final VillagerRecord oldMerchant=merchantRecord;
		final VillagerRecord newMerchant=destInn.merchantRecord;

		transferVillager(merchantRecord, destInn, false);
		destInn.transferVillager(destInn.merchantRecord, this, false);

		visitorsList.add("panels.merchantmovedout;"+oldMerchant.getName()+";"+oldMerchant.getNativeOccupationName()
				+";"+destInn.getTownHall().getVillageQualifiedName()+";"+nbNightsMerchant);

		destInn.visitorsList.add("panels.merchantmovedout;"+newMerchant.getName()+";"+newMerchant.getNativeOccupationName()
				+";"+getTownHall().getVillageQualifiedName()+";"+nbNightsMerchant);

		visitorsList.add("panels.merchantarrived;"+newMerchant.getName()+";"+newMerchant.getNativeOccupationName()
				+";"+destInn.getTownHall().getVillageQualifiedName());

		destInn.visitorsList.add("panels.merchantarrived;"+oldMerchant.getName()+";"+oldMerchant.getNativeOccupationName()
				+";"+getTownHall().getVillageQualifiedName());

		if (MLN.LogMerchant >= MLN.MAJOR) {
			MLN.major(this, "Swaped merchant " + oldMerchant + " and "
					+ newMerchant + " with " + destInn.getTownHall());
		}

		merchantRecord = newMerchant;
		destInn.merchantRecord = oldMerchant;

		nbNightsMerchant = 0;
		destInn.nbNightsMerchant = 0;

		destInn.saveTownHall("merchant moved");
		saveNeeded = true;
		saveReason = "Swapped merchant";
	}

	public int takeGoods(int item, int toTake) {
		return takeGoods(item, 0, toTake);
	}

	public int takeGoods(int item, int meta, int toTake) {
		int taken = 0;

		int i = 0;
		while ((taken < toTake) && (i < chests.size())) {
			final TileEntityChest chest = chests.get(i).getMillChest(worldObj);

			if (chest != null) {
				taken += MillCommonUtilities.getItemsFromChest(chest, item, meta, toTake
						- taken);
			}

			i++;
		}

		i = 0;
		while ((taken < toTake) && (i < furnaces.size())) {
			final TileEntityFurnace furnace = (TileEntityFurnace) worldObj
					.getBlockTileEntity(furnaces.get(i).getiX(), furnaces
							.get(i).getiY(), furnaces.get(i).getiZ());

			if (furnace != null) {
				taken += MillCommonUtilities.getItemsFromFurnace(furnace, item, toTake
						- taken);
			}

			i++;
		}

		return taken;
	}

	public int takeGoods(InvItem item, int toTake) {
		return takeGoods(item.id(), item.meta, toTake);
	}

	public void testModeGoods() {
		if (isTownhall && !villageType.lonebuilding) {
			final int stored=storeGoods(Mill.denier_or.itemID, 64);

			if (stored<64) {
				MLN.error(this, "Should have stored 64 test goods but stored only "+stored);
			}

			storeGoods(Mill.summoningWand.itemID, 1);
			if (culture.key.equals("hindi")) {
				storeGoods(Mill.indianstatue.itemID, 64);
				storeGoods(Mill.stone_decoration.blockID, 0, 2048);
				storeGoods(Mill.stone_decoration.blockID, 1, 2048);
				storeGoods(Block.sandStone.blockID, 2048);
				storeGoods(Block.stone.blockID, 2048);
				storeGoods(Block.cobblestone.blockID, 512);
			} else if (culture.key.equals("mayan")) {
				storeGoods(Block.sandStone.blockID, 512);
				storeGoods(Block.stone.blockID, 3500);
				storeGoods(Block.cobblestone.blockID, 2048);
				storeGoods(Mill.stone_decoration.blockID, 2, 64);
				storeGoods(Block.wood.blockID, 1, 512);
			} else if (culture.key.equals("japanese")) {
				storeGoods(Block.sapling.blockID, 64);
				storeGoods(Mill.wood_decoration.blockID, 2, 2048);
				storeGoods(Block.gravel.blockID, 512);
				storeGoods(Mill.paperWall.blockID, 2048);
				storeGoods(Block.stone.blockID, 2048);
				storeGoods(Block.cobblestone.blockID, 1024);
				storeGoods(Mill.wood_decoration.blockID, 0, 512);
				storeGoods(Mill.wood_decoration.blockID, 1, 512);
				storeGoods(Block.wood.blockID,1, 512);
			} else if (culture.key.equals("byzantines")) {
				storeGoods(Block.glass.blockID, 512);
				storeGoods(Block.cobblestone.blockID, 1500);
				storeGoods(Block.stone.blockID, 1500);
				storeGoods(Block.brick.blockID, 512);
				storeGoods(Block.sandStone.blockID, 512);
				storeGoods(Block.cloth.blockID, 11, 64);
				storeGoods(Block.cloth.blockID, 14, 64);
				storeGoods(Block.wood.blockID, 2, 128);
				storeGoods(Block.bookShelf.blockID, 0, 64);
				storeGoods(Mill.byzantine_tiles.blockID, 128);
				storeGoods(Mill.byzantine_tile_slab.blockID, 128);
				storeGoods(Mill.byzantine_stone_tiles.blockID,128);

			} else {
				storeGoods(Block.glass.blockID, 512);
				storeGoods(Block.cobblestone.blockID, 2048);
				storeGoods(Block.stone.blockID, 3500);
				storeGoods(Mill.wood_decoration.blockID, 0, 2048);
				storeGoods(Mill.wood_decoration.blockID, 1, 2048);
				storeGoods(Block.cloth.blockID, 11, 64);
				storeGoods(Block.cloth.blockID, 14, 64);
			}
			storeGoods(Block.wood.blockID, 1024);
			storeGoods(Item.ingotIron.itemID, 256);
			storeGoods(Block.cloth.blockID, 64);
		}

	}

	public void testShowGroundLevel() {


		for (int i=0;i<winfo.length;i++) {
			for (int j=0;j<winfo.width;j++) {

				final Point p=new Point(winfo.mapStartX+i,winfo.topGround[i][j]-1,winfo.mapStartZ+j);

				if (MillCommonUtilities.getBlock(worldObj, p)!=Mill.lockedChest.blockID) {
					if (!winfo.topAdjusted[i][j]) {
						MillCommonUtilities.setBlockAndMetadata(worldObj, p, Block.cloth.blockID,pathing.regions[i][j]%16);
					} else {
						MillCommonUtilities.setBlockAndMetadata(worldObj, p, Block.blockIron.blockID,0);
					}
				}

			}
		}
	}

	@Override
	public String toString() {
		if (location != null)
			return "("+location + "/" + getVillageQualifiedName()+"/"+worldObj+")";
		else
			return super.toString();
	}

	public void transferVillager(VillagerRecord vr, Building dest, boolean raid) {

		if (MLN.LogDiplomacy>=MLN.MINOR) {
			MLN.minor(this, "Transfering villager "+vr+" to "+dest+". Raid: "+raid);
		}

		if (!raid) {//permanent departure, removing record
			removeVillagerRecord(vr.id);
			getTownHall().removeVillagerRecord(vr.id);
		} else {//temporary departure, flagging the record
			vr.awayraiding=true;
			getTownHall().getVillagerRecordById(vr.id).awayraiding=true;

			if (MLN.LogDiplomacy>=MLN.DEBUG) {
				MLN.debug(this, "Set record to away raiding");
			}
		}

		final VillagerRecord newRecord=vr.clone();
		newRecord.housePos=dest.getPos();
		newRecord.townHallPos=dest.getTownHall().getPos();
		newRecord.raidingVillage=raid;
		newRecord.awayraiding=false;

		if (raid) {
			newRecord.originalVillagePos=getTownHall().getPos();
			newRecord.raiderSpawn=worldObj.getWorldTime();//recording transfer time (to respawn villager after delay)
		}

		dest.vrecords.add(newRecord);
		if (!dest.isTownhall) {
			dest.getTownHall().vrecords.add(newRecord.clone());
		}

		final MillVillager v=getVillagerById(vr.id);
		if (v!=null) {
			villagers.remove(v);
			getTownHall().villagers.remove(v);

			if (!raid && dest.getTownHall().isActive) {//immediate transfer
				v.setHousePoint(dest.getPos());
				v.setTownHallPoint(dest.getTownHall().getPos());

				v.isRaider=false;
				v.setPosition(dest.getSleepingPos().getiX(), dest.getSleepingPos().getiY(),
						dest.getSleepingPos().getiZ());

				dest.villagers.add(v);
				dest.getTownHall().villagers.add(v);

				if (MLN.LogDiplomacy>=MLN.DEBUG) {
					MLN.debug(this, "Villager entity moved.");
				}
			} else {
				v.despawnVillager();

				if (MLN.LogDiplomacy>=MLN.DEBUG) {
					MLN.debug(this, "Villager entity despawned.");
				}
			}
		}

		//immediate save of the distant TH as it might be frozen
		dest.getTownHall().saveTownHall("incoming villager");
	}

	private void unloadChunks() {
		if ((chunkLoader!=null) && chunkLoader.chunksLoaded) {
			chunkLoader.unloadChunks();
		}
	}

	public void unlockAllChests() {
		chestLocked = false;
		for (final Point p : buildings) {
			final Building b = mw.getBuilding(p);
			if (b != null) {
				b.unlockChests();
			}
		}

		if (this.countGoods(Mill.negationWand.itemID)==0) {
			storeGoods(Mill.negationWand.itemID,1);
		}
	}

	public void unlockChests() {

		if (!isMarket) {
			chestLocked = false;
			for (final Point p : chests) {
				final TileEntityMillChest chest = p.getMillChest(worldObj);
				if (chest != null) {
					chest.buildingPos=getPos();
				}
			}
		}
	}

	private void updateAchievements() {

		if (villageType==null)
			return;

		final List<Entity> players = MillCommonUtilities.getEntitiesWithinAABB(
				worldObj, EntityPlayer.class, getPos(), villageType.radius, 20);

		for (final Entity ent : players) {

			final EntityPlayer player=(EntityPlayer)ent;

			if (villageType.lonebuilding) {
				player.addStat(MillAchievements.explorer, 1);
			}

			if (location.tags.contains(tagHoF)) {
				player.addStat(MillAchievements.pantheon, 1);
			}

			final int nbcultures=mw.nbCultureInGeneratedVillages();

			if (nbcultures>=3) {
				player.addStat(MillAchievements.marcopolo, 1);
			}
			if (nbcultures>=Culture.vectorCultures.size()) {
				player.addStat(MillAchievements.magellan, 1);
			}

		}

	}

	private void updateArchiveSigns() {

		if (worldObj.isRemote)
			return;

		final EntityPlayer player = worldObj.getClosestPlayer(pos.getiX(), pos.getiY(),
				pos.getiZ(), 16);

		if (player == null)
			return;// nobody to see the signs anyway

		if ((System.currentTimeMillis() - lastSignUpdate) < 10000)
			return;

		if (signs.size() == 0)
			return;

		for (int i = 0; i < signs.size(); i++) {
			final Point p = signs.get(i);
			if ((p != null) && (MillCommonUtilities.getBlock(worldObj, p) != Mill.panel.blockID)) {
				final int meta = MillCommonUtilities.guessSignMetaData(worldObj, p);

				if (meta > 0) {
					MillCommonUtilities.setBlockAndMetadata(worldObj, p,
							Mill.panel.blockID, meta);
				}
			} else if (p == null) {
				MLN.error(this, "The pos of sign "+i+" is null.");
			}
		}

		int signId=0;

		for (final VillagerRecord vr : getTownHall().vrecords) {

			if (!vr.raidingVillage && !vr.getType().visitor && (signs.get(signId)!=null)) {
				final TileEntityPanel sign = signs.get(signId).getPanel(worldObj);

				if (sign!=null) {

					String[][] lines=null;

					if (vr.awayraiding) {
						lines=new String[][] { {vr.firstName}, {vr.familyName},
								{""}, {"panels.awayraiding"} };
					} else if (vr.awayhired) {
						lines=new String[][] { {vr.firstName}, {vr.familyName},
								{""}, {"panels.awayhired"} };
					} else if (vr.killed) {
						lines=new String[][] { {vr.firstName}, {vr.familyName},
								{""}, {"panels.dead"} };
					} else {
						final MillVillager villager = getTownHall().getVillagerById(vr.id);
						if (villager == null) {
							lines=new String[][] { {vr.firstName}, {vr.familyName},
									{""}, {"panels.missing"} };

						} else if (!villager.isVisitor()) {
							final String distance = ""+Math.floor(getPos().distanceTo(villager));

							final String direction=getPos().directionTo(villager.getPos());
							String occupation = "";

							if ((villager.goalKey != null)
									&& Goal.goals.containsKey(villager.goalKey)) {
								occupation = "goal."+villager.goalKey;
							}

							if (occupation.length() > 15) {
								occupation = occupation.substring(0, 10) + "(...)";
							}

							lines=new String[][] { {vr.firstName}, {vr.familyName},
									{"other.shortdistancedirection",distance,direction}, {occupation} };
						}
						ServerSender.updatePanel(mw,signs.get(signId), lines, TileEntityPanel.archives, townHallPos, vr.id);
					}
				}
			}
			signId++;

			if (signId>=signs.size()) {
				break;
			}
		}

		for (int i=signId;i<signs.size();i++) {
			final TileEntityPanel sign = signs.get(i).getPanel(worldObj);

			if (sign!=null) {

				final String[][] lines=new String[][] { {"ui.reservedforvillager1"}, {"ui.reservedforvillager2"},
						{""}, {"#" + (i + 1)} };
				ServerSender.updatePanel(mw,signs.get(i), lines, 0, townHallPos, 0);
			}
		}

		lastSignUpdate = System.currentTimeMillis();
	}

	public void updateBackgroundVillage() {

		if (worldObj.isRemote)
			return;


		if ((villageType==null) || !isTownhall || (location==null))
			return;

		final EntityPlayer player = worldObj.getClosestPlayer(pos.getiX(), pos.getiY(),
				pos.getiZ(), MLN.BackgroundRadius);

		//Raid won't start if not within background radius
		if (player!=null) {
			if (worldObj.isDaytime()) {
				nightBackgroundActionPerformed = false;
			} else if (!nightBackgroundActionPerformed) {
				if (villageType.carriesRaid && (raidTarget==null) && (MillCommonUtilities.randomInt(100)<MLN.RaidingRate)) {
					if (MLN.LogDiplomacy>=MLN.DEBUG) {
						MLN.debug(this, "Calling attemptPlanNewRaid");
					}
					attemptPlanNewRaid();
				}
				nightBackgroundActionPerformed=true;
			}
		}

		//No condition for a raid update
		if (((worldObj.getWorldTime()%24000) > 23500) && (raidTarget!=null)) {
			if (!updateRaidPerformed) {

				if (MLN.LogDiplomacy>=MLN.DEBUG) {
					MLN.debug(this, "Calling updateRaid for raid: "+raidPlanningStart+"/"+raidStart+"/"+worldObj.getWorldTime());
				}

				updateRaid();
				updateRaidPerformed=true;
			}
		} else {
			updateRaidPerformed=false;
		}

		//if (mod_Millenaire.instance.isServer() && worldObj.getWorldTime()%20==0)
		//sendVillagePacket(false);
	}

	public void updateBuildingClient() {
		if ((location!=null) && (pos!=null)) {
			if (isTownhall && (villageType != null) && (location!=null)) {
				if (lastVillagerRecordsRepair == 0) {
					lastVillagerRecordsRepair = worldObj.getWorldTime();
				} else if ((worldObj.getWorldTime() - lastVillagerRecordsRepair) >= 100) {
					repairVillagerListClient();
					lastVillagerRecordsRepair = worldObj.getWorldTime();
				}
			}
		}
	}

	public void updateBuildingServer() {

		//should never happen
		if (worldObj.isRemote) {
			updateBuildingClient();
			return;
		}

		if (location==null)
			return;

		final EntityPlayer player = worldObj.getClosestPlayer(pos.getiX(), pos.getiY(),
				pos.getiZ(), MLN.KeepActiveRadius);

		if (isTownhall) {
			if ((player != null) && (getPos().distanceTo(player) < MLN.KeepActiveRadius)) {
				loadChunks();
			} else if ((player == null) || (getPos().distanceTo(player) > (MLN.KeepActiveRadius+32))) {
				unloadChunks();
			}
			isAreaLoaded=isVillageChunksLoaded();


			if (isActive && (!isAreaLoaded)) {
				isActive = false;

				for (final Object o : worldObj.playerEntities) {
					final EntityPlayer p=(EntityPlayer) o;
					this.sendBuildingPacket(p, false);
				}

				saveTownHall("becoming inactive");
			} else if (!isActive && isAreaLoaded) {

				for (final Object o : worldObj.playerEntities) {
					final EntityPlayer p=(EntityPlayer) o;
					this.sendBuildingPacket(p, false);
				}

				isActive = true;
			}
			if (!isActive)
				return;
		} else {
			if ((getTownHall() == null) || !getTownHall().isActive)
				return;
		}

		if (location == null)
			return;

		try {
			if (isTownhall && (villageType != null)) {
				updateTownHall();
			}

			if (location.tags.contains(tagArchives)) {
				updateArchiveSigns();
			}

			if (location.tags.contains(tagGrove)) {
				updateGrove();
			}

			if (location.tags.contains(tagKiln)) {
				updateKiln();
			}

			if (spawns.size() > 0) {
				updatePens();
			}

			if (healingspots.size() > 0) {
				updateHealingSpots();
			}

			if ((mobSpawners.size() > 0) && (player != null)
					&& (pos.distanceToSquared(player) < (20 * 20))) {
				updateMobSpawners();
			}

			if (dispenderUnknownPowder.size() > 0) {
				updateDispensers();
			}

			if (netherwartsoils.size() > 0) {
				updateNetherWartSoils();
			}

			if (isHouse() && (!isTownhall || !location.showTownHallSigns)) {
				updateHouseSign();
			}


			if (isInn) {
				updateInn();
			}

			if (isMarket) {
				updateMarket(false);
			}

			if (isTownhall) {
				if (saveNeeded) {
					saveTownHall("Save needed");
				} else if ((worldObj.getWorldTime() - lastSaved) > 1000) {
					saveTownHall("Delay up");
				}
			}

			if ((player!=null) && (location.getPlan()!=null) && (location.getPlan().exploreTag != null)) {
				checkExploreTag(player);
			}

			sendInitialBuildingPackets();

			if (MillCommonUtilities.chanceOn(100)) {
				for (final Point p : chests) {
					if (p.getMillChest(worldObj)!=null) {
						p.getMillChest(worldObj).buildingPos=getPos();
					}
				}
			}

			refreshGoods();

		} catch (final Exception e) {
			Mill.proxy.sendChatAdmin(MLN.string("ui.updateEntity"));
			MLN.error(this, "Exception in TileEntityBuilding.onUpdate(): ");
			MLN.printException(e);
		}
	}

	private void updateDispensers() {

		for (final Point p : dispenderUnknownPowder) {

			if (MillCommonUtilities.chanceOn(5000)) {

				final TileEntityDispenser dispenser = p.getDispenser(worldObj);
				if (dispenser != null) {
					MillCommonUtilities.putItemsInChest(dispenser,
							Mill.unknownPowder.itemID, 1);
				}
			}

		}
	}

	private void updateGrove() {
		for (final Point p : woodspawn) {
			if (MillCommonUtilities.chanceOn(4000)
					&& (MillCommonUtilities.getBlock(worldObj, p) == Block.sapling.blockID)) {
				growTree(worldObj, p.getiX(), p.getiY(), p.getiZ(),
						MillCommonUtilities.random);
			}
		}
	}

	private void updateHealingSpots() {

		if ((worldObj.getWorldTime()%100)==0) {

			for (final Point p : healingspots) {

				final EntityPlayer player = worldObj.getClosestPlayer(p.getiX(), p.getiY(),
						p.getiZ(), 4);

				if ((player!=null) && (player.getHealth()<player.getMaxHealth())) {
					player.setEntityHealth(player.getHealth()+1);
					ServerSender.sendTranslatedSentence(player, MLN.LIGHTGREEN, "other.buildinghealing", getNativeBuildingName());
				}

			}
		}

	}

	private void updateHouseSign() {

		if (worldObj.isRemote)
			return;

		if (signs.size() == 0)
			return;

		if ((pos==null) || (location==null))
			return;

		final EntityPlayer player = worldObj.getClosestPlayer(pos.getiX(), pos.getiY(),pos.getiZ(), 16);

		if (player == null)
			return;// nobody to see the signs anyway

		if ((System.currentTimeMillis() - lastSignUpdate) < 10000)
			return;

		VillagerRecord wife = null, husband = null;

		for (final VillagerRecord vr : getTownHall().vrecords) {
			if (getPos().equals(vr.housePos)) {
				if ((vr.gender == MillVillager.FEMALE) && !((vr.getType()!=null) && vr.getType().isChild)) {
					wife = vr;
				}
				if ((vr.gender == MillVillager.MALE) && !((vr.getType()!=null) && vr.getType().isChild)) {
					husband = vr;
				}
			}
		}

		final Point p = signs.get(0);

		if (p == null)
			return;

		if (worldObj.getBlockId(p.getiX(), p.getiY(), p.getiZ()) != Mill.panel.blockID) {

			final int meta = MillCommonUtilities.guessSignMetaData(worldObj, p);

			if (meta > 0) {
				MillCommonUtilities.setBlockAndMetadata(worldObj, p, Mill.panel.blockID, meta);
			}
		}

		final TileEntityPanel panel = p.getPanel(worldObj);

		if (panel == null) {
			MLN.error(this, "No TileEntitySign at: " + p);
		} else {

			String[][] lines=null;

			if ((husband != null) && (wife != null)) {
				lines=new String[][]{{"panels.nameand",wife.firstName},
						{husband.firstName}, {husband.familyName}, {""} };
			} else if (husband != null) {
				lines=new String[][]{
						{husband.firstName}, {""}, {husband.familyName}, {""} };
			} else if (wife != null) {
				lines=new String[][]{
						{wife.firstName}, {""}, {wife.familyName}, {""} };
			} else {
				lines=new String[][]{
						{"ui.currentlyempty1"}, {""}, {"ui.currentlyempty2"}, {""} };
			}

			ServerSender.updatePanel(mw,p, lines, TileEntityPanel.house, getPos(), 0);
		}

		lastSignUpdate = System.currentTimeMillis();
	}

	private void updateInn() {

		if (worldObj.isDaytime()) {
			thNightActionPerformed = false;
		} else if (!thNightActionPerformed) {
			if (merchantRecord != null) {
				nbNightsMerchant++;

				if (nbNightsMerchant > 1) {
					attemptMerchantMove(false);
				}
			}
			thNightActionPerformed = true;
		}

		updateInnSign();
	}

	private void updateInnSign() {

		if (worldObj.isRemote)
			return;

		final EntityPlayer player = worldObj.getClosestPlayer(pos.getiX(), pos.getiY(),
				pos.getiZ(), 20);

		if (player == null)
			return;// nobody to see the signs anyway

		if (signs.size() == 0)
			return;

		for (int i = 0; i < signs.size(); i++) {
			final Point p = signs.get(i);
			if ((p != null)
					&& (MillCommonUtilities.getBlock(worldObj, p) != Mill.panel.blockID)) {
				final int meta = MillCommonUtilities.guessSignMetaData(worldObj, p);

				if (meta > 0) {
					MillCommonUtilities.setBlockAndMetadata(worldObj, p,
							Mill.panel.blockID, meta);
				}
			}
		}

		TileEntityPanel sign = signs.get(0).getPanel(worldObj);

		if (sign != null) {
			final String[][] lines=new String[][]{ {getNativeBuildingName()}, {""},
					{"ui.visitorslist1"},
					{"ui.visitorslist2"}};

			ServerSender.updatePanel(mw,signs.get(0), lines,  TileEntityPanel.innVisitors, getPos(), 0);
		}

		if (signs.size() < 2)
			return;

		sign = signs.get(1).getPanel(worldObj);

		if (sign != null) {
			final String[][] lines=new String[][]{ {"ui.goodstraded"}, {""},
					{"ui.import_total",""+MillCommonUtilities.getInvItemHashTotal(imported)},
					{"ui.export_total",""+MillCommonUtilities.getInvItemHashTotal(exported)}};

			ServerSender.updatePanel(mw,signs.get(1), lines,  TileEntityPanel.tradeGoods, getPos(), 0);
		}
	}

	private void updateKiln() {
		for (final Point p : brickspot) {
			if (MillCommonUtilities.getBlock(worldObj, p) == Block.grass.blockID) {
				MillCommonUtilities.setBlock(worldObj, p, Block.dirt.blockID);
			}
		}
	}

	public void updateMarket(boolean devAttempt) throws MillenaireException {

		if (worldObj.isDaytime() && !devAttempt) {
			thNightActionPerformed = false;
		} else if (!thNightActionPerformed || devAttempt) {

			final int maxMerchants=stalls.size();

			if ((vrecords.size() < maxMerchants) && MillCommonUtilities.chanceOn(2)) {
				if (MLN.LogMerchant >= MLN.MAJOR) {
					MLN.major(this, "Attempting to create a foreign merchant.");
				}

				VillagerType type;

				final Vector<VillagerType> merchantTypesOtherVillages=new Vector<VillagerType>();
				for (final Point p : getTownHall().relations.keySet()) {
					if (getTownHall().relations.get(p)>RELATION_VERYGOOD) {
						final Building distantVillage=mw.getBuilding(p);
						if ((distantVillage!=null) && (distantVillage.culture!=getTownHall().culture) && (distantVillage.getBuildingsWithTag(tagMarket).size()>0)) {
							merchantTypesOtherVillages.add(distantVillage.culture.getRandomForeignMerchant());
						}
					}
				}

				//20% chance of foreign merchant with one village, up to 50% with 4
				final int foreignChance=Math.min(1+merchantTypesOtherVillages.size(),5);

				if ((merchantTypesOtherVillages.size()>0) && (MillCommonUtilities.randomInt(11)<foreignChance)) {

					if (merchantTypesOtherVillages.size()==0) {
						type = culture.getRandomForeignMerchant();
					} else {
						type = merchantTypesOtherVillages.get(MillCommonUtilities.randomInt(merchantTypesOtherVillages.size()));
					}

				} else {
					type = culture.getRandomForeignMerchant();
				}


				final MillVillager merchant = MillVillager.createVillager(type.culture,
						type.key, 0, worldObj, sleepingPos, getPos(), townHallPos,false,
						null, null);

				addOrReplaceVillager(merchant);
				final VillagerRecord merchantRecord = new VillagerRecord(mw,merchant);
				addOrReplaceRecord(merchantRecord);
				worldObj.spawnEntityInWorld(merchant);

				for (final InvItem iv : merchant.vtype.foreignMerchantStock.keySet()) {
					storeGoods(iv.id(),iv.meta,merchant.vtype.foreignMerchantStock.get(iv));
				}

				if (MLN.LogMerchant >= MLN.MAJOR) {
					MLN.major(this, "Created foreign merchant: "
							+ merchantRecord);
				}

			}

			thNightActionPerformed = true;
		}

		updateMarketSigns();
	}

	private void updateMarketSigns() {

		final EntityPlayer player = worldObj.getClosestPlayer(pos.getiX(), pos.getiY(),
				pos.getiZ(), 20);

		if (player == null)
			return;// nobody to see the signs anyway

		if (signs.size() == 0)
			return;

		for (int i = 0; i < signs.size(); i++) {
			final Point p = signs.get(i);
			if ((p != null)
					&& (MillCommonUtilities.getBlock(worldObj, p) != Mill.panel.blockID)) {
				final int meta = MillCommonUtilities.guessSignMetaData(worldObj, p);

				if (meta > 0) {
					MillCommonUtilities.setBlockAndMetadata(worldObj, p,
							Mill.panel.blockID, meta);
				}
			}
		}

		final TileEntityPanel sign = signs.get(0).getPanel(worldObj);

		if (sign != null) {

			final String[][] lines=new String[][]{ {getNativeBuildingName()}, {""},
					{"ui.merchants"}, {"" + vrecords.size()}};
			ServerSender.updatePanel(mw,signs.get(0), lines, TileEntityPanel.marketMerchants, getPos(), 0);
		}
	}

	private void updateMobSpawners() {

		for (int i = 0; i < mobSpawners.size(); i++) {
			for (int j = 0; j < mobSpawners.get(i).size(); j++) {
				if (MillCommonUtilities.chanceOn(180)) {
					final int bid = MillCommonUtilities.getBlock(worldObj, mobSpawners
							.get(i).get(j));

					if (bid == Block.mobSpawner.blockID) {

						final List<Entity> mobs = MillCommonUtilities
								.getEntitiesWithinAABB(worldObj,
										EntityMob.class, mobSpawners.get(i)
										.get(j), 10, 5);
						int nbmob = 0;
						for (final Entity ent : mobs) {
							if (EntityList.getEntityString(ent).equals(
									mobSpawnerTypes.get(i))) {
								nbmob++;
							}
						}

						if (nbmob < 4) {
							MillCommonUtilities.spawnMobsSpawner(worldObj, mobSpawners
									.get(i).get(j), mobSpawnerTypes.get(i));
						}
					}
				}
			}
		}
	}

	private void updateNetherWartSoils() {
		for (final Point p :netherwartsoils) {

			if (MillCommonUtilities.chanceOn(1000)) {
				if (MillCommonUtilities.getBlock(worldObj, p.getAbove())==Block.netherStalk.blockID) {
					final int meta=MillCommonUtilities.getBlockMeta(worldObj, p.getAbove());

					if (meta<3) {
						MillCommonUtilities.setBlockMetadata(worldObj, p.getAbove(), meta+1);
					}
				}
			}
		}
	}

	private void updatePens() {
		if (!worldObj.isDaytime() && (vrecords.size() > 0) && !worldObj.isRemote) {

			int nbMaxRespawn=0;
			for (final Vector<Point> spawnPoints : spawns) {
				nbMaxRespawn+=spawnPoints.size();
			}


			if (nbAnimalsRespawned<=nbMaxRespawn) {
				int sheep = 0, cow = 0, pig = 0, chicken = 0;

				final List<Entity> animals = MillCommonUtilities.getEntitiesWithinAABB(
						worldObj, EntityAnimal.class, getPos(), 15, 5);

				for (final Entity animal : animals) {
					if (animal instanceof EntitySheep) {
						sheep++;
					} else if (animal instanceof EntityPig) {
						pig++;
					} else if (animal instanceof EntityCow) {
						cow++;
					} else if (animal instanceof EntityChicken) {
						chicken++;
					}
				}

				for (int i = 0; i < spawns.size(); i++) {
					int nb = 0;
					if (spawnTypes.get(i).equals(Mill.ENTITY_SHEEP)) {
						nb = sheep;
					} else if (spawnTypes.get(i).equals(Mill.ENTITY_CHICKEN)) {
						nb = chicken;
					} else if (spawnTypes.get(i).equals(Mill.ENTITY_PIG)) {
						nb = pig;
					} else if (spawnTypes.get(i).equals(Mill.ENTITY_COW)) {
						nb = cow;
					}

					for (int j = 0; j < (spawns.get(i).size() - nb); j++) {
						if (MillCommonUtilities.chanceOn(100)) {
							final EntityAnimal animal = (EntityAnimal) EntityList
									.createEntityByName(spawnTypes.get(i),
											worldObj);
							final Point pen = spawns.get(i).get(
									MillCommonUtilities.randomInt(spawns.get(i).size()));
							animal.setPosition(pen.getiX()+0.5, pen.getiY(), pen.getiZ()+0.5);
							worldObj.spawnEntityInWorld(animal);
							nbAnimalsRespawned++;
						}
					}
				}
			}
		} else {
			nbAnimalsRespawned=0;
		}
	}

	private void updateRaid() {

		//raid has been planned long enough, time for action
		if ((worldObj.getWorldTime()>(raidPlanningStart+24000)) && (raidStart==0)) {

			if (MLN.LogDiplomacy>=MLN.MINOR) {
				MLN.minor(this, "Starting raid on "+mw.getBuilding(raidTarget));
			}

			startRaid();

		} else if ((raidStart>0) && (worldObj.getWorldTime()>(raidStart+23000))) {

			final Building distantVillage=mw.getBuilding(raidTarget);

			if (distantVillage!=null) {
				//target is frozen and the raid has been going on for a day. Time to end it.
				if (!distantVillage.isActive) {
					endRaid();
				}
			} else {//target seems gone; destroyed by player?
				cancelRaid();
				for (final VillagerRecord vr : vrecords) {
					vr.awayraiding=false;//they'll get respawned normally now
				}
			}
		}
	}

	private void updateTownHall() throws MillenaireException {
		if (vrecords.size() > 0) {
			updateWorldInfo();
		}

		closestPlayer = worldObj.getClosestPlayer(pos.getiX(), pos.getiY(),
				pos.getiZ(), 100);

		completeConstruction();

		findBuildingProject();
		findBuildingConstruction();

		if (location.showTownHallSigns) {
			updateTownHallSigns(false);
		}

		checkSeller();
		checkWorkers();

		if ((worldObj.getWorldTime()%10)==0) {
			checkBattleStatus();

		}

		killMobs();

		if ((vrecords.size() > 0)
				&& !worldObj.isDaytime()
				&& ((System.currentTimeMillis() - lastPathingUpdate) > PATHING_REBUILD_DELAY)) {
			rebuildPathing(false);
		}

		if (!declaredPos && (worldObj != null)) {
			if (villageType.lonebuilding) {
				mw.registerLoneBuildingsLocation(
						worldObj, getPos(), getVillageQualifiedName(), villageType,
						culture, false);
			} else {
				mw
				.registerVillageLocation(worldObj, getPos(), getVillageQualifiedName(), villageType, culture, false);
			}
			declaredPos = true;
		}

		if (lastVillagerRecordsRepair == 0) {
			lastVillagerRecordsRepair = worldObj.getWorldTime();
		} else if ((worldObj.getWorldTime() - lastVillagerRecordsRepair) >= 100) {
			repairVillagerList();
			lastVillagerRecordsRepair = worldObj.getWorldTime();
		}

		if (worldObj.isDaytime()) {
			thNightActionPerformed = false;
		} else if (!thNightActionPerformed) {

			if (!villageType.playerControlled && !villageType.lonebuilding) {

				for (final EntityPlayer player : MillCommonUtilities.getServerPlayers(worldObj)) {
					final UserProfile profile=MillCommonUtilities.getServerProfile(worldObj, player.username);
					profile.adjustDiplomacyPoint(this, 5);
				}

				for (final Point p : relations.keySet()) {
					if (MillCommonUtilities.chanceOn(10)) {
						final Building village=mw.getBuilding(p);
						if (village != null) {

							final int relation=relations.get(p);

							int improveChance;

							if (relation<RELATION_OPENCONFLICT) {
								improveChance=0;
							} else if (relation<RELATION_VERYBAD) {
								improveChance=30;
							} else if (relation<0) {
								improveChance=40;
							} else if (relation>RELATION_EXCELLENT) {
								improveChance=100;
							} else if (relation>RELATION_GOOD) {
								improveChance=70;
							} else {
								improveChance=60;
							}

							if (MillCommonUtilities.randomInt(100)<improveChance) {
								if (relations.get(p)<RELATION_MAX) {
									adjustRelation(p,10+MillCommonUtilities.randomInt(10));
									ServerSender.sendTranslatedSentenceInRange(worldObj, getPos(), MLN.BackgroundRadius,MLN.DARKGREEN,"ui.relationfriendly",getVillageQualifiedName(),village.getVillageQualifiedName(),MillCommonUtilities.getRelationName(relations.get(p)));
								}
							} else {
								if (relations.get(p)>RELATION_MIN) {
									adjustRelation(p,-10-MillCommonUtilities.randomInt(10));
									ServerSender.sendTranslatedSentenceInRange(worldObj, getPos(), MLN.BackgroundRadius,MLN.ORANGE,"ui.relationunfriendly",getVillageQualifiedName(),village.getVillageQualifiedName(),MillCommonUtilities.getRelationName(relations.get(p)));
								}
							}
						}
					}
				}
			}

			thNightActionPerformed = true;
		}

		if (villageType.playerControlled
				&& ((worldObj.getWorldTime() % 1000) == 0)
				&& (countGoods(
						Mill.parchmentVillageScroll.itemID, -1) == 0)) {

			for (int i = 0; i < mw.villagesList.pos
					.size(); i++) {
				final Point p = mw.villagesList.pos
						.get(i);
				if (getPos().sameBlock(p)) {
					storeGoods(
							Mill.parchmentVillageScroll.itemID,
							i, 1);
				}
			}
		}

		if ((controlledBy!=null) && (controlledBy.length()>0) && (controlledByName==null)) {
			final UserProfile profile=MillCommonUtilities.getServerProfile(worldObj,controlledBy);
			controlledByName=profile.playerName;
		}

		if ((worldObj.getWorldTime() % 200)==0) {
			updateAchievements();
		}

		if (autobuildPaths) {
			clearOldPaths();
			constructCalculatedPaths();
		}
	}

	private void updateTownHallSigns(boolean forced) {

		if (worldObj.isRemote)
			return;

		final EntityPlayer player = worldObj.getClosestPlayer(pos.getiX(), pos.getiY(),
				pos.getiZ(), 20);

		if (player == null)
			return;// nobody to see the signs anyway

		if (!forced && ((System.currentTimeMillis() - lastSignUpdate) < 2000))
			return;

		if ((signs.size() == 0) || (signs.get(0) == null))
			return;


		for (int i = 0; i < signs.size(); i++) {
			final Point p = signs.get(i);
			if ((p != null)
					&& (MillCommonUtilities.getBlock(worldObj, p) != Mill.panel.blockID)) {
				final int meta = MillCommonUtilities.guessSignMetaData(worldObj, p);

				if (meta > 0) {
					MillCommonUtilities.setBlockAndMetadata(worldObj, p,
							Mill.panel.blockID, meta);
				}
			}
		}

		TileEntityPanel sign = (TileEntityPanel) worldObj
				.getBlockTileEntity(signs.get(0).getiX(), signs.get(0).getiY(),
						signs.get(0).getiZ());

		if (sign != null) {

			int nbvill = 0;

			for (final VillagerRecord vr : vrecords) {
				if (vr!=null) {
					final boolean belongsToVillage=(!vr.raidingVillage && (vr.getType()!=null) && !vr.getType().visitor);

					if (belongsToVillage) {
						nbvill++;
					}
				}
			}

			String[][] lines;

			if ((controlledBy!=null) && (controlledBy.length()>0)) {
				lines=new String[][]{{ getVillageNameWithoutQualifier()}, {qualifier}, {villageType.name},
						{controlledByName}};
			} else {
				lines=new String[][]{{ getVillageNameWithoutQualifier()}, {qualifier}, {villageType.name},
						{"ui.populationnumber",""+nbvill}};
			}
			ServerSender.updatePanel(mw,signs.get(0), lines,  TileEntityPanel.etatCivil, getPos(), 0);
		}

		if (signs.size() == 1)
			return;

		final BuildingPlan goalPlan = getCurrentGoalBuildingPlan();

		final Vector<InvItem> res = new Vector<InvItem>();
		final Vector<Integer> resCost = new Vector<Integer>();
		final Vector<Integer> resHas = new Vector<Integer>();

		if (goalPlan != null) {
			for (final InvItem key : goalPlan.resCost.keySet()) {
				res.add(key);
				resCost.add(goalPlan.resCost.get(key));
				int has = countGoods(key.id(), key.meta);
				if ((builder != null) && (buildingLocationIP != null)
						&& buildingLocationIP.key.equals(buildingGoal)) {
					has += builder.countInv(key.id(), key.meta);
				}
				if (has > goalPlan.resCost.get(key)) {
					has = goalPlan.resCost.get(key);
				}

				resHas.add(has);
			}
		}

		for (int i = 1; (i < 4) && (i < signs.size()); i++) {
			sign = signs.get(i).getPanel(worldObj);

			if (sign != null) {

				String[][] lines;
				if ((goalPlan == null) || (res.size() < ((i * 2) - 1))) {
					lines = new String[][] { {""}, {""}, {""}, {""}};
				} else {
					if ((res.size() > 6) && (i == 3)) {

						lines =  new String[][] {{(res.get((i * 2) - 2).getTranslationKey())},
								{"ui.xoutofy",""+resHas.get((i * 2) - 2),""+resCost.get((i * 2) - 2)},
								{"ui.extraresneeded1","" + (res.size() - 5)},
								{"ui.extraresneeded2","" + (res.size() - 5)}};
					} else if (res.size() > ((i * 2) - 1)) {

						lines =  new String[][] {
								{res.get((i * 2) - 2).getTranslationKey()},
								{"ui.xoutofy",""+resHas.get((i * 2) - 2),""+resCost.get((i * 2) - 2)},
								{res.get((i * 2) - 1).getTranslationKey()},
								{"ui.xoutofy",""+resHas.get((i * 2) - 1),""+resCost.get((i * 2) - 1)}};
					} else {
						lines =  new String[][] {
								{res.get((i * 2) - 2).getTranslationKey()},
								{"ui.xoutofy",""+resHas.get((i * 2) - 2),""+ resCost.get((i * 2) - 2)}
								, {""}, {""} };
					}
				}

				ServerSender.updatePanel(mw,signs.get(i), lines,  TileEntityPanel.resources, getPos(), 0);
			}
		}

		if (signs.size() < 5)
			return;

		// project:
		sign = signs.get(4).getPanel(worldObj);

		if (sign != null) {
			String[][] lines;
			if (buildingGoal == null) {

				lines =  new String[][] {{ ""},
						{"ui.goalscompleted1"},
						{"ui.goalscompleted2"}, {""}};

			} else {
				final BuildingPlan goal = this.getCurrentGoalBuildingPlan();


				String[] status;
				if ((buildingLocationIP != null)
						&& buildingLocationIP.key.equals(buildingGoal)) {
					if (buildingLocationIP.level == 0) {
						status = new String[]{"ui.inconstruction"};
					} else {
						status = new String[]{"ui.upgrading",""+buildingLocationIP.level};
					}
				} else {
					status = new String[] {buildingGoalIssue};
				}

				lines =  new String[][] {{"ui.project"},
						{goal.nativeName}, {goal.getGameNameKey()}, status };
			}

			int type;
			if (villageType.playerControlled) {
				type = TileEntityPanel.controlledProjects;
			} else {
				type = TileEntityPanel.projects;
			}
			ServerSender.updatePanel(mw,signs.get(4), lines, type, getPos(), 0);
		}

		if (signs.size() < 6)
			return;

		// actual construction:
		sign = signs.get(5).getPanel(worldObj);

		if (sign != null) {
			String[][] lines;
			if (buildingLocationIP == null) {
				lines =  new String[][] {{""},
						{"ui.noconstruction1"},
						{"ui.noconstruction2"}, {""}};
			} else {
				final String planName = culture.getBuildingPlanSet(buildingLocationIP.key).getNativeName();

				String[] status;
				if (buildingLocationIP.level == 0) {
					status = new String[]{"ui.inconstruction"};
				} else {
					status = new String[]{"ui.upgrading",""+buildingLocationIP.level};
				}

				String[] loc;

				if (buildingLocationIP != null) {

					final int distance = MathHelper.floor_double(getPos()
							.distanceTo(buildingLocationIP.pos));

					final String direction = getPos().directionTo(
							buildingLocationIP.pos);

					loc = new String[]{"other.shortdistancedirection",""+distance,""+direction};
				} else {
					loc = new String[]{};
				}

				String[] constr;

				if ((getBblocks() != null) && (getBblocks().length>0)) {
					constr = new String[]{"ui.construction",""+(int)Math.floor((bblocksPos * 100) / getBblocks().length)};
				} else {
					constr = new String[]{"ui.inconstruction"};
				}

				lines =  new String[][] {constr, {planName}, status,
						loc };
			}

			ServerSender.updatePanel(mw,signs.get(5), lines, TileEntityPanel.constructions, getPos(), 0);
		}

		if (signs.size() < 7)
			return;

		//Etat civil
		sign = signs.get(6).getPanel(worldObj);

		if (sign != null) {

			int nbMen = 0, nbFemale = 0, nbGrownBoy = 0, nbGrownGirl = 0, nbBoy = 0, nbGirl = 0;

			String[][] lines;

			for (final VillagerRecord vr : vrecords) {
				final boolean belongsToVillage=(vr.getType()!=null) && !vr.getType().visitor && !vr.raidingVillage;

				if (belongsToVillage) {

					if (!vr.getType().isChild) {
						if (vr.gender == MillVillager.MALE) {
							nbMen++;
						} else {
							nbFemale++;
						}
					} else {
						if (vr.villagerSize == MillVillager.max_child_size) {
							if (vr.gender == MillVillager.MALE) {
								nbGrownBoy++;
							} else {
								nbGrownGirl++;
							}
						} else {
							if (vr.gender == MillVillager.MALE) {
								nbBoy++;
							} else {
								nbGirl++;
							}
						}
					}

				}
			}

			lines =  new String[][] {
					{"ui.population"},
					{"ui.adults",""+(nbMen + nbFemale),""+nbMen,""+nbFemale},
					{"ui.teens",""+(nbGrownBoy + nbGrownGirl),""+nbGrownBoy,""+nbGrownGirl},
					{"ui.children",""+(nbBoy + nbGirl),""+nbBoy,""+nbGirl}};

			ServerSender.updatePanel(mw,signs.get(6), lines, TileEntityPanel.etatCivil, getPos(), 0);

		}

		if (signs.size() < 8)
			return;

		//Village map
		sign = signs.get(7).getPanel(worldObj);

		if (sign != null) {

			final String[][] lines= new String[][] {
					{"ui.villagemap"},{""},
					{"ui.nbbuildings",""+buildings.size()},
					{""}};

			ServerSender.updatePanel(mw,signs.get(7), lines, TileEntityPanel.villageMap, getPos(), 0);
		}

		if (signs.size() < 9)
			return;

		//military
		sign = signs.get(8).getPanel(worldObj);

		if (sign != null) {

			String status="";

			if (raidTarget!=null)
				if (raidStart>0) {
					status="panels.raidinprogress";
				} else {
					status="panels.planningraid";
				}
			else {
				if (underAttack) {
					status="panels.underattack";
				}
			}

			final String[][] lines= new String[][] {
					{"panels.military"}, {status},
					{"panels.offense",""+this.getVillageRaidingStrength()},
					{"panels.defense",""+this.getVillageDefendingStrength()}};

			ServerSender.updatePanel(mw,signs.get(8), lines, TileEntityPanel.military, getPos(), 0);
		}

		lastSignUpdate = System.currentTimeMillis();
	}

	public void updateVillagerRecord(MillVillager v) {
		final VillagerRecord vr=getVillagerRecordById(v.villager_id);

		if (vr!=null) {
			vr.updateRecord(v);
		}
	}

	public void updateWorldInfo() throws MillenaireException {

		if (villageType == null) {
			MLN.error(this, "updateWorldInfo: villageType is null");
			return;
		}

		final boolean areaChanged = winfo.update(worldObj, getLocations(),
				buildingLocationIP, location.pos, villageType.radius);

		if (areaChanged) {
			rebuildPathing(false);
		}
	}

	private void validateVillagerList() {

		for (final MillVillager v : villagers) {
			if (v == null) {
				MLN.error(this, "Null value in villager list");
			}
			if (v.isDead && (MLN.LogTileEntityBuilding >= MLN.MINOR)) {
				MLN.minor(this, "Villager " + v + " is dead.");
			}

			final Vector<VillagerRecord> found = new Vector<VillagerRecord>();
			for (final VillagerRecord vr : vrecords) {
				if (vr.matches(v)) {
					found.add(vr);
				}
			}

			if (found.size() == 0) {
				MLN.error(this, "Villager " + v + " not present in records.");
			} else if (found.size() > 1) {
				MLN.error(this, "Villager " + v + " present " + found.size()
						+ " times in records: ");
				for (final VillagerRecord vr : found) {
					MLN.major(this, vr.toString() + " / " + vr.hashCode());
				}
			}
		}

		for (final VillagerRecord vr : vrecords) {
			final Vector<MillVillager> found = new Vector<MillVillager>();

			if (vr.housePos == null) {
				MLN.error(this, "Record " + vr + " has no house.");
			}

			for (final MillVillager v : villagers) {
				if (vr.matches(v)) {
					found.add(v);
				}
			}
			if (found.size() != vr.nb) {
				MLN.error(this, "Record " + vr + " present " + found.size()
						+ " times in villagers, previously: " + vr.nb
						+ ". Villagers: ");
				for (final MillVillager v : found) {
					MLN.major(this, v.toString() + " / " + v.hashCode());
				}
				vr.nb = found.size();
			}
		}
	}

	private void writeBblocks() {

		final File buildingsDir = MillCommonUtilities.getBuildingsDir(worldObj);

		final File file1 = new File(buildingsDir, getPos().getPathString()
				+ "_bblocks_temp.bin");

		BuildingBlock[] blocks=getBblocks();

		if (blocks!=null) {
			try {
				final FileOutputStream fos = new FileOutputStream(
						file1);

				final DataOutputStream ds = new DataOutputStream(fos);

				ds.writeInt(blocks.length);

				for (int i=0;i<blocks.length;i++ ) {
					final BuildingBlock b=blocks[i];
					ds.writeInt(b.p.getiX());
					ds.writeShort(b.p.getiY());
					ds.writeInt(b.p.getiZ());
					ds.writeShort(b.bid);
					ds.writeByte(b.meta);
					ds.writeByte(b.special);
				}

				ds.close();
				fos.close();

				file1.renameTo(new File(buildingsDir, getPos().getPathString()
						+ "_bblocks.bin"));

			} catch (final IOException e) {
				MLN.printException("Error when writing bblocks: ",e);
			}
		} else {
			file1.delete();
		}
		bblocksChanged=false;
	}

	private void writePaths() {
		final File buildingsDir = MillCommonUtilities.getBuildingsDir(worldObj);

		File file1 = new File(buildingsDir, getPos().getPathString()
				+ "_paths_temp.bin");

		if (pathsToBuild!=null) {
			try {


				final FileOutputStream fos = new FileOutputStream(
						file1);

				final DataOutputStream ds = new DataOutputStream(fos);

				ds.writeInt(pathsToBuild.size());

				for (final Vector<BuildingBlock> path : pathsToBuild) {
					ds.writeInt(path.size());

					for (final BuildingBlock b:path) {
						ds.writeInt(b.p.getiX());
						ds.writeShort(b.p.getiY());
						ds.writeInt(b.p.getiZ());
						ds.writeShort(b.bid);
						ds.writeByte(b.meta);
						ds.writeByte(b.special);
					}
				}
				ds.close();
				fos.close();

				file1.renameTo(new File(buildingsDir, getPos().getPathString()
						+ "_paths.bin"));

			} catch (final IOException e) {
				MLN.printException("Error when writing pathsToBuild: ",e);
			}
		} else {
			file1.delete();
		}

		file1 = new File(buildingsDir, getPos().getPathString()
				+ "_pathstoclear_temp.bin");

		if (oldPathPointsToClear!=null) {
			try {
				final FileOutputStream fos = new FileOutputStream(
						file1);

				final DataOutputStream ds = new DataOutputStream(fos);

				ds.writeInt(oldPathPointsToClear.size());

				for (final Point p : oldPathPointsToClear) {
					ds.writeInt(p.getiX());
					ds.writeShort(p.getiY());
					ds.writeInt(p.getiZ());
				}
				ds.close();
				fos.close();

				file1.renameTo(new File(buildingsDir, getPos().getPathString()
						+ "_pathstoclear.bin"));

			} catch (final IOException e) {
				MLN.printException("Error when writing oldPathPointsToClear: ",e);
			}
		} else {
			file1.delete();
		}

		pathsChanged=false;
	}


	public void writeToNBT(NBTTagCompound nbttagcompound) {

		if (location==null) {
			MLN.error(this, "Null location. Skipping write.");
			return;
		}


		nbttagcompound.setString("versionCompatibility", versionCompatibility);

		try {

			pos.write(nbttagcompound, "pos");

			location.write(nbttagcompound, "buildingLocation","self");

			nbttagcompound.setBoolean("chestLocked", chestLocked);

			if ((name != null) && (name.length() > 0)) {
				nbttagcompound.setString("name", name);
			}
			nbttagcompound.setString("qualifier", qualifier);
			nbttagcompound.setBoolean("isTownhall", isTownhall);
			nbttagcompound.setString("culture", culture.key);

			if (villageType != null) {
				nbttagcompound.setString("villageType", villageType.key);
			}

			if (controlledBy != null) {
				nbttagcompound.setString("controlledBy", controlledBy);
			}

			if (sleepingPos != null) {
				sleepingPos.write(nbttagcompound, "spawnPos");
			}
			if (sellingPos != null) {
				sellingPos.write(nbttagcompound, "sellingPos");
			}
			if (craftingPos != null) {
				craftingPos.write(nbttagcompound, "craftingPos");
			}
			if (defendingPos != null) {
				defendingPos.write(nbttagcompound, "defendingPos");
			}
			if (shelterPos != null) {
				shelterPos.write(nbttagcompound, "shelterPos");
			}
			if (pathStartPos != null) {
				pathStartPos.write(nbttagcompound, "pathStartPos");
			}
			if (leasurePos != null) {
				leasurePos.write(nbttagcompound, "leasurePos");
			}
			if (townHallPos != null) {
				townHallPos.write(nbttagcompound, "townHallPos");
			}

			nbttagcompound.setBoolean("nightActionPerformed", thNightActionPerformed);
			nbttagcompound.setBoolean("nightBackgroundActionPerformed", nightBackgroundActionPerformed);

			NBTTagList nbttaglist;

			nbttaglist = new NBTTagList();
			for (final Point p : signs) {
				if (p != null) {
					final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
					p.write(nbttagcompound1, "pos");
					nbttaglist.appendTag(nbttagcompound1);
				}
			}
			nbttagcompound.setTag("signs", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : netherwartsoils) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("netherwartsoils", nbttaglist);



			nbttaglist = new NBTTagList();
			for (final Point p : silkwormblock) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("silkwormblock", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : sugarcanesoils) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("sugarcanesoils", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : fishingspots) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("fishingspots", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : healingspots) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("healingspots", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : stalls) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("stalls", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : woodspawn) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("woodspawn", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : brickspot) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("brickspot", nbttaglist);

			nbttaglist = new NBTTagList();
			for (int i = 0; i < spawns.size(); i++) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				nbttagcompound1.setString("type", spawnTypes.get(i));
				final NBTTagList nbttaglist2 = new NBTTagList();
				for (final Point p : spawns.get(i)) {
					final NBTTagCompound nbttagcompound2 = new NBTTagCompound();
					p.write(nbttagcompound2, "pos");
					nbttaglist2.appendTag(nbttagcompound2);
				}
				nbttagcompound1.setTag("points", nbttaglist2);
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("spawns", nbttaglist);

			nbttagcompound.setInteger("nbAnimalsRespawned", nbAnimalsRespawned);

			nbttaglist = new NBTTagList();
			for (int i = 0; i < soilTypes.size(); i++) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				nbttagcompound1.setString("type", soilTypes.get(i));
				final NBTTagList nbttaglist2 = new NBTTagList();
				for (final Point p : soils.get(i)) {
					final NBTTagCompound nbttagcompound2 = new NBTTagCompound();
					p.write(nbttagcompound2, "pos");
					nbttaglist2.appendTag(nbttagcompound2);
				}
				nbttagcompound1.setTag("points", nbttaglist2);
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("genericsoils", nbttaglist);

			nbttaglist = new NBTTagList();
			for (int i = 0; i < mobSpawners.size(); i++) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				nbttagcompound1.setString("type", mobSpawnerTypes.get(i));
				final NBTTagList nbttaglist2 = new NBTTagList();
				for (final Point p : mobSpawners.get(i)) {
					final NBTTagCompound nbttagcompound2 = new NBTTagCompound();
					p.write(nbttagcompound2, "pos");
					nbttaglist2.appendTag(nbttagcompound2);
				}
				nbttagcompound1.setTag("points", nbttaglist2);
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("mobspawns", nbttaglist);

			nbttaglist = new NBTTagList();
			for (int i = 0; i < sources.size(); i++) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				nbttagcompound1.setInteger("type", sourceTypes.get(i));
				final NBTTagList nbttaglist2 = new NBTTagList();
				for (final Point p : sources.get(i)) {
					final NBTTagCompound nbttagcompound2 = new NBTTagCompound();
					p.write(nbttagcompound2, "pos");
					nbttaglist2.appendTag(nbttagcompound2);
				}
				nbttagcompound1.setTag("points", nbttaglist2);
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("sources", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : chests) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("chests", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : furnaces) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("furnaces", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : brewingStands) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("brewingStands", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final Point p : buildings) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("buildings", nbttaglist);

			nbttagcompound.setInteger("bblocksPos", bblocksPos);

			if (bblocksChanged) {
				writeBblocks();
				if (MLN.LogHybernation >= MLN.MAJOR) {
					MLN.major(this, "Saved bblocks.");
				}
			}

			nbttaglist = new NBTTagList();
			for (final Vector<BuildingProject> projectsLevel : buildingProjects) {
				for (final BuildingProject project : projectsLevel) {
					if (project.location != null) {
						final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
						project.location.write(nbttagcompound1, "location","buildingProjects");
						nbttaglist.appendTag(nbttagcompound1);
						if (MLN.LogHybernation >= MLN.MAJOR) {
							MLN.major(this, "Writing building location: "
									+ project.location + " (level: "
									+ project.location.level + ", variation: "
									+ project.location.getVariation() + ")");
						}
					}
				}
			}
			nbttagcompound.setTag("locations", nbttaglist);

			if (buildingGoal != null) {
				nbttagcompound.setString("buildingGoal", buildingGoal);
				if (MLN.LogHybernation >= MLN.MAJOR) {
					MLN.major(this, "Writing building goal: " + buildingGoal);
				}
			}
			nbttagcompound.setInteger("buildingGoalLevel", buildingGoalLevel);
			nbttagcompound.setInteger("buildingGoalVariation",
					buildingGoalVariation);

			if (buildingGoalIssue != null) {
				nbttagcompound
				.setString("buildingGoalIssue", buildingGoalIssue);
			}
			if (buildingGoalLocation != null) {
				buildingGoalLocation.write(nbttagcompound,
						"buildingGoalLocation","buildingGoalLocation");
				if (MLN.LogHybernation >= MLN.MAJOR) {
					MLN.major(this, "Writing buildingGoalLocation: "
							+ buildingGoalLocation);
				}
			}

			if (buildingLocationIP != null) {
				buildingLocationIP.write(nbttagcompound, "buildingLocationIP","buildingLocationIP");

				if (MLN.LogHybernation >= MLN.MAJOR) {
					MLN.major(this, "Writing buildingLocationIP: "
							+ buildingLocationIP);
				}
			}

			nbttaglist = new NBTTagList();
			for (final VillagerRecord vr : vrecords) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				vr.write(nbttagcompound1, "vr");
				nbttaglist.appendTag(nbttagcompound1);
				if (MLN.LogHybernation >= MLN.DEBUG) {
					MLN.debug(this, "Writing VR: " + vr);
				}
			}
			nbttagcompound.setTag("villagersrecords", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final String s : visitorsList) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				nbttagcompound1.setString("visitor", s);
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("visitorsList", nbttaglist);


			nbttaglist = new NBTTagList();
			for (final String s : buildingsBought) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				nbttagcompound1.setString("key", s);
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("buildingsBought", nbttaglist);

			nbttagcompound.setBoolean("updateRaidPerformed", this.updateRaidPerformed);
			nbttagcompound.setBoolean("nightBackgroundActionPerformed", this.nightBackgroundActionPerformed);
			nbttagcompound.setBoolean("nightActionPerformed", this.thNightActionPerformed);
			nbttagcompound.setBoolean("underAttack", this.underAttack);

			if (raidTarget!=null) {
				raidTarget.write(nbttagcompound, "raidTarget");
				nbttagcompound.setLong("raidPlanningStart", this.raidPlanningStart);
				nbttagcompound.setLong("raidStart", this.raidStart);
			}

			nbttaglist = new NBTTagList();
			for (final String s : raidsPerformed) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				nbttagcompound1.setString("raid", s);
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("raidsPerformed", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final String s : raidsSuffered) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				nbttagcompound1.setString("raid", s);
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("raidsTaken", nbttaglist);

			if ((villageType!=null) && !villageType.lonebuilding) {

				nbttaglist = new NBTTagList();
				for (final Point p : relations.keySet()) {

					final Building dv=mw.getBuilding(p);

					if ((dv!=null) && !dv.villageType.lonebuilding) {
						final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
						p.write(nbttagcompound1, "pos");
						nbttagcompound1.setInteger("value", relations.get(p));
						nbttaglist.appendTag(nbttagcompound1);
					}
				}
				nbttagcompound.setTag("relations", nbttaglist);
			}

			if (parentVillage!=null) {
				parentVillage.write(nbttagcompound, "parentVillage");
			}

			nbttaglist = new NBTTagList();
			for (final InvItem good : imported.keySet()) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				nbttagcompound1.setInteger("itemid", good.id());
				nbttagcompound1.setInteger("itemmeta", good.meta);
				nbttagcompound1.setInteger("quantity", imported.get(good));
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("importedGoods", nbttaglist);

			nbttaglist = new NBTTagList();
			for (final InvItem good : exported.keySet()) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				nbttagcompound1.setInteger("itemid", good.id());
				nbttagcompound1.setInteger("itemmeta", good.meta);
				nbttagcompound1.setInteger("quantity", exported.get(good));
				nbttaglist.appendTag(nbttagcompound1);
			}
			nbttagcompound.setTag("exportedGoods", nbttaglist);

			if (MLN.LogTileEntityBuilding >= MLN.DEBUG) {
				MLN.debug(this, "Saving building. Location: " + location
						+ ", pos: " + getPos());
			}

			nbttaglist = new NBTTagList();
			for (final Point p : subBuildings) {
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				p.write(nbttagcompound1, "pos");
				nbttaglist.appendTag(nbttagcompound1);

			}
			nbttagcompound.setTag("subBuildings", nbttaglist);

			if (pujas!=null) {
				final NBTTagCompound tag = new NBTTagCompound();
				pujas.writeToNBT(tag);
				nbttagcompound.setTag("pujas", tag);
			}

			nbttagcompound.setLong("lastGoodsRefresh", lastGoodsRefresh);

			nbttagcompound.setInteger("pathsToBuildIndex", pathsToBuildIndex);
			nbttagcompound.setInteger("pathsToBuildPathIndex", pathsToBuildPathIndex);
			nbttagcompound.setInteger("oldPathPointsToClearIndex", oldPathPointsToClearIndex);

			if (pathsChanged) {
				writePaths();
			}


		} catch (final Exception e) {
			Mill.proxy.sendChatAdmin("Error when trying to save building. Check millenaire.log.");
			MLN.error(this, "Exception in Villager.onUpdate(): ");
			MLN.printException(e);
		}
	}
}
