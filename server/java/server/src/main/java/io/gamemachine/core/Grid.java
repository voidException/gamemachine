package io.gamemachine.core;

/*
 * Implements fast 2d spatial hashing.  Neighbor queries return all entities that are in our cell and neighboring cells.  The bounding is a box not a radius,
 * so there is no way to get all entities within an exact range.  This is normally not an issue for large numbers of entities in a large open space, and if you are
 * working with a small number of entities you can afford to do additional filtering client side.
 * 
 * Grids are instantiated with a size and a cell size.  The grid is divided into cells of cell size.  The cell size must divide evenly into the grid size.
 * 
 * Internally all coordinates are stored as integers with a precision scale of scaleFactor.  Clients do the math to convert floats to integers and back to floats.
 * Space is more important then precision here.
 * 
 * When possible we send delta's to clients instead of full coordinates,and clients can send us delta's of their movement instead of full coordinates.  We know to send
 * a delta when we have recently sent info to the client on the same entity.  If a client has not seen an entity recently we send the full coordinates.
 * 
 * Clients should initially send a TrackData with full coordinates.  The entity tracking system will send the client a TrackDataResponse with
 * reason RESEND if we do not have a full coordinate yet.
 * 
 * 
 * 
 * 
 */
import io.gamemachine.messages.TrackData;
import io.gamemachine.messages.TrackData.EntityType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Grid {

	private int max;
	private int cellSize = 0;
	private float convFactor;
	private int width;
	private int cellCount;
	private int scaleFactor = 100;
	private int shortIdQueueSize = 1000;

	private static final Logger logger = LoggerFactory.getLogger(Grid.class);
	
	private PriorityBlockingQueue<Integer> shortIdQueue = new PriorityBlockingQueue<Integer>();	
	private ConcurrentHashMap<String, Integer> shortIds = new ConcurrentHashMap<String, Integer>();
	private ConcurrentHashMap<String, ConcurrentHashMap<String, GridValue>> gridValues = new ConcurrentHashMap<String, ConcurrentHashMap<String, GridValue>>();

	private ConcurrentHashMap<String, TrackData> objectIndex = new ConcurrentHashMap<String, TrackData>();
	private ConcurrentHashMap<String, Integer> cellsIndex = new ConcurrentHashMap<String, Integer>();
	private ConcurrentHashMap<Integer, ConcurrentHashMap<String, TrackData>> cells = new ConcurrentHashMap<Integer, ConcurrentHashMap<String, TrackData>>();

	public Grid(int max, int cellSize) {
		this.max = max;
		this.cellSize = cellSize;
		this.convFactor = 1.0f / this.cellSize;
		this.width = (int) (this.max / this.cellSize);
		this.cellCount = this.width * this.width;
		
		for(int i=1; i<this.shortIdQueueSize; i++){
			shortIdQueue.put(i);
		}
	}

	public class GridValue {

		private TrackData trackData;
		private int x;
		private int y;
		private long lastSend = System.currentTimeMillis();

		public GridValue(TrackData trackData, int shortId) {
			this.x = trackData.x;
			this.y = trackData.y;

			this.trackData = cloneTrackData(trackData);
			this.trackData.shortId = shortId;
			this.trackData.id = null;
		}

		private TrackData cloneTrackData(TrackData trackData) {
			TrackData clone = trackData.clone();
			clone.x = null;
			clone.y = null;
			clone.z = null;
			clone.getNeighbors = null;
			clone.dynamicMessage = trackData.dynamicMessage;
			clone.direction = trackData.direction;
			clone.speed = trackData.speed;
			clone.velocity = trackData.velocity;
			return clone;
		}
	}

	public void releaseShortId(String playerId) {
		if (shortIds.containsKey(playerId)) {
			int shortId = shortIds.get(playerId);
			shortIdQueue.put(shortId);
			shortIds.remove(playerId);
		}
	}
	
	public Integer getShortId(String playerId) {
		if (shortIds.containsKey(playerId)) {
			return shortIds.get(playerId);
		} else {
			try {
				Integer shortId = shortIdQueue.poll(10, TimeUnit.MILLISECONDS);
				if (shortId == null) {
					logger.warn("Unable to get short id from queue");
					return null;
				}
				
				shortIds.put(playerId, shortId);
				return shortId;
			} catch (InterruptedException e) {
				e.printStackTrace();
				return null;
			}
		}
	}
	
	public void dumpGrid() {
		for (TrackData td : objectIndex.values()) {
			System.out.println("id=" + td.id + " x=" + td.x / this.scaleFactor + " y=" + td.y / this.scaleFactor);
		}
	}

	public int getObjectCount() {
		return objectIndex.size();
	}

	public int getMax() {
		return this.max;
	}

	public int getCellSize() {
		return this.cellSize;
	}

	public int getWidth() {
		return this.width;
	}

	public int getCellCount() {
		return this.cellCount;
	}

	private ConcurrentHashMap<String, GridValue> gridValuesForPlayer(String playerId) {
		ConcurrentHashMap<String, GridValue> playerGridValues = gridValues.get(playerId);
		if (playerGridValues == null) {
			playerGridValues = new ConcurrentHashMap<String, GridValue>();
			gridValues.put(playerId, playerGridValues);
		}
		return playerGridValues;
	}

	public Set<Integer> cellsWithinBounds(int x, int y) {
		Set<Integer> cells = new HashSet<Integer>();

		int offset = this.cellSize;

		int startX = (x - offset);
		int startY = (y - offset);

		// subtract one from offset to keep it from hashing to the next cell
		// boundary outside of range
		int endX = (x + offset - 1);
		int endY = (y + offset - 1);

		for (int rowNum = startX; rowNum <= endX; rowNum += offset) {
			for (int colNum = startY; colNum <= endY; colNum += offset) {
				if (rowNum >= 0 && colNum >= 0) {
					cells.add(hash(rowNum, colNum));
				}
			}
		}
		return cells;
	}

	public ArrayList<TrackData> neighbors(String playerId, int px, int py, EntityType entityType, int optsFlag) {
		int x = px / this.scaleFactor;
		int y = py / this.scaleFactor;
		ArrayList<TrackData> result;

		Collection<TrackData> trackDatas;
		result = new ArrayList<TrackData>();
		Set<Integer> cells = cellsWithinBounds(x, y);
		long currentTime = System.currentTimeMillis();

		ConcurrentHashMap<String, GridValue> playerGridValues = gridValuesForPlayer(playerId);

		for (int cell : cells) {
			trackDatas = gridValuesInCell(cell);
			if (trackDatas == null) {
				continue;
			}

			for (TrackData trackData : trackDatas) {
				if (trackData == null) {
					continue;
				}

				if (trackData.id.equals(playerId)) {
					continue;
				}

				if (entityType == null || trackData.entityType == entityType) {
					if (optsFlag == 2) {
						result.add(trackData);
					} else {

						GridValue gridValue = playerGridValues.get(trackData.id);

						if (gridValue == null) {
							Integer shortId = getShortId(trackData.id);
							if (shortId == null) {
								logger.warn("Unable to obtain short id");
								continue;
							}
							trackData.shortId = shortId;
							gridValue = new GridValue(trackData, shortId);
							playerGridValues.put(trackData.id, gridValue);
							result.add(trackData);
						} else if ((currentTime - gridValue.lastSend) > 100) {
							playerGridValues.remove(trackData.id);
							result.add(trackData);
						} else {
							updateGridValue(gridValue, trackData);
							gridValue.lastSend = currentTime;
							result.add(gridValue.trackData);
						}
					}
				}
			}
		}
		return result;
	}

	private void updateGridValue(GridValue gridValue, TrackData trackData) {
		if (trackData.x >= gridValue.x) {
			gridValue.trackData.ix = trackData.x - gridValue.x;
		} else {
			gridValue.trackData.ix = -(gridValue.x - trackData.x);
		}

		if (trackData.y >= gridValue.y) {
			gridValue.trackData.iy = trackData.y - gridValue.y;
		} else {
			gridValue.trackData.iy = -(gridValue.y - trackData.y);
		}
		gridValue.x = trackData.x;
		gridValue.y = trackData.y;
	}

	public Collection<TrackData> gridValuesInCell(int cell) {
		ConcurrentHashMap<String, TrackData> cellGridValues = cells.get(cell);

		if (cellGridValues != null) {
			return cellGridValues.values();
		} else {
			return null;
		}
	}

	public ArrayList<TrackData> getNeighborsFor(String playerId, String id, EntityType entityType, int optsFlag) {
		TrackData gridValue = get(id);
		if (gridValue == null) {
			return null;
		}
		return neighbors(playerId, gridValue.x, gridValue.y, entityType, optsFlag);
	}

	public List<TrackData> getAll() {
		return new ArrayList<TrackData>(objectIndex.values());
	}

	public TrackData get(String id) {
		return objectIndex.get(id);
	}

	public void remove(String playerId) {
		TrackData indexValue = objectIndex.get(playerId);
		if (indexValue != null) {
			int cell = cellsIndex.get(playerId);
			ConcurrentHashMap<String, TrackData> cellGridValues = cells.get(cell);
			if (cellGridValues != null) {
				cellGridValues.remove(playerId);
			}
			objectIndex.remove(playerId);
			cellsIndex.remove(playerId);
			gridValues.remove(playerId);
			releaseShortId(playerId);
		}
	}

	public Boolean set(String id, int x, int y, int z, EntityType entityType) {
		TrackData trackData = new TrackData();
		trackData.id = id;
		trackData.x = x;
		trackData.y = y;
		trackData.z = z;
		trackData.entityType = entityType;
		trackData.setGetNeighbors(0);
		return set(trackData);
	}

	private TrackData updateFromDelta(TrackData deltaTrackData) {

		TrackData trackData = objectIndex.get(deltaTrackData.id);
		if (trackData == null) {
			return null;
		}

		trackData.x += deltaTrackData.ix;
		trackData.y += deltaTrackData.iy;

		if (deltaTrackData.hasDynamicMessage()) {
			trackData.dynamicMessage = deltaTrackData.dynamicMessage;
		}

		if (deltaTrackData.hasDirection()) {
			trackData.direction = deltaTrackData.direction;
		}

		if (deltaTrackData.hasSpeed()) {
			trackData.speed = deltaTrackData.speed;
		}

		return trackData;
	}

	public Boolean set(TrackData newTrackData) {
		Integer shortId = getShortId(newTrackData.id);
		if (shortId == null) {
			return false;
		}
		
		TrackData trackData = null;
		if (newTrackData.hasIx()) {
			trackData = updateFromDelta(newTrackData);
			if (trackData == null) {
				logger.debug("Delta update with no original " + newTrackData.id);
				return false;
			}
		} else {
			trackData = newTrackData;
		}

		String id = trackData.id;

		Integer oldCellValue = cellsIndex.get(id);

		int cell = hash(trackData.x / this.scaleFactor, trackData.y / this.scaleFactor);

		if (oldCellValue != null) {
			if (oldCellValue != cell) {
				ConcurrentHashMap<String, TrackData> cellGridValues = cells.get(oldCellValue);
				if (cellGridValues != null && cellGridValues.containsKey(id)) {
					cellGridValues.remove(id);
				}
				if (cellGridValues != null && cellGridValues.size() == 0) {
					cells.remove(oldCellValue);
				}

			}
			objectIndex.replace(id, trackData);
			cellsIndex.replace(id, cell);
		} else {
			cellsIndex.put(id, cell);
			objectIndex.put(id, trackData);
		}

		ConcurrentHashMap<String, TrackData> cellGridValues = cells.get(cell);
		if (cellGridValues == null) {
			cellGridValues = new ConcurrentHashMap<String, TrackData>();
			cellGridValues.put(id, trackData);
			cells.put(cell, cellGridValues);
		} else {
			cellGridValues.put(id, trackData);
		}

		return true;
	}

	public int hash2(int x, int y) {
		return (int) (Math.floor(x / this.cellSize) + Math.floor(y / this.cellSize) * width);
	}

	public int hash(int x, int y) {
		return (int) ((x * this.convFactor)) + (int) ((y * this.convFactor)) * this.width;
	}
}
