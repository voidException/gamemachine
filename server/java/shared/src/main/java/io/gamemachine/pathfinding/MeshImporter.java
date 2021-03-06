package io.gamemachine.pathfinding;

import io.gamemachine.client.messages.GridData;
import io.gamemachine.client.messages.GridNode;
import io.gamemachine.client.messages.PathData;
import io.gamemachine.client.messages.TriangleMesh;
import io.gamemachine.client.messages.Vector3;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import com.badlogic.gdx.ai.pfa.DefaultConnection;
import com.google.common.io.Files;

public class MeshImporter {

	public static GridGraph importGrid() {
		try {
			byte[] bytes = Files.toByteArray(new File("/home/chris/game_machine/server/java/shared/grid_data.bin"));
			GridData gridData = GridData.parseFrom(bytes);
			return createGridGraph(gridData);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	public static GridGraph createGridGraph(GridData gridData) {
		int h = gridData.h;
		int w = gridData.w;
		System.out.println("width=" + w + " height=" + h);
		int index = 0;
		GridGraph graph = new GridGraph(w, h);

		for (GridNode gridNode : gridData.nodes) {
			Vector3 point = gridNode.point;
			Node node = new Node();
			if (gridNode.slope == null) {
				gridNode.slope = 0f;
			}

			node.slope = gridNode.slope;

			if (point.x == null) {
				point.x = 0f;
			}
			if (point.y == null) {
				point.y = 0f;
			}
			if (point.z == null) {
				point.z = 0f;
			}

			// System.out.println(point.x+" "+point.y+" "+point.z);
			node.position = new io.gamemachine.util.Vector3(point.x, point.z, point.y);

			if (node.slope > GridGraph.maxSlope) {
				graph.nodes[(int) node.position.x][(int) node.position.y] = null;
				continue;
			}

			graph.nodes[(int) node.position.x][(int) node.position.y] = node;

			node.index = index;
			graph.nodeIndex.put(node.index, node);
			graph.grid.set(node.index, node.position.x, node.position.y, node.position.z);
			index++;
		}

		System.out.println("Nodecount=" + index);
		int connectionCount = 0;

		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				Node node = graph.nodes[x][y];
				for (Node neighbor : neighbors(graph.nodes, x, y)) {
					if (addConnection(node, neighbor)) {
						connectionCount++;
					}
				}
			}
		}
		System.out.println("connection count " + connectionCount);
		System.out.println("nodeIndex size " + graph.nodeIndex.size());
		return graph;
	}

	public static boolean inBounds(Node[][] nodes, int a, int b) {
		try {
			if (a == -1 || a == nodes.length || b == -1 || b == nodes[a].length) {
				return false;
			} else {
				return true;
			}
		} catch (Exception e) {
			System.out.println("exception out of bounds " + a + "." + b);
			throw new RuntimeException("Out of bounds");
		}
	}

	public static List<Node> neighbors(Node[][] nodes, int x, int y) {
		List<Node> neighbors = new ArrayList<Node>();

		int a, b;

		a = x - 1;
		b = y;
		if (inBounds(nodes, a, b)) {
			Node left = nodes[a][b];
			neighbors.add(left);
		}

		a = x + 1;
		b = y;
		if (inBounds(nodes, a, b)) {
			Node right = nodes[a][b];
			neighbors.add(right);
		}

		a = x;
		b = y + 1;
		if (inBounds(nodes, a, b)) {
			Node up = nodes[a][b];
			neighbors.add(up);
		}

		a = x;
		b = y - 1;
		if (inBounds(nodes, a, b)) {
			Node down = nodes[a][b];
			neighbors.add(down);
		}

		if (GridGraph.useDiagonals) {
			a = x + 1;
			b = y + 1;
			if (inBounds(nodes, a, b)) {
				Node topright = nodes[a][b];
				neighbors.add(topright);
			}

			a = x - 1;
			b = y + 1;
			if (inBounds(nodes, a, b)) {
				Node topleft = nodes[a][b];
				neighbors.add(topleft);
			}

			a = x + 1;
			b = y - 1;
			if (inBounds(nodes, a, b)) {
				Node botright = nodes[a][b];
				neighbors.add(botright);
			}

			a = x - 1;
			b = y - 1;
			if (inBounds(nodes, a, b)) {
				Node botleft = nodes[a][b];
				neighbors.add(botleft);
			}
		}
		return neighbors;
	}

	public static boolean addConnection(Node node, Node other) {
		boolean valid = false;
		double slopeDiff = 0.0f;
		if (node == null || other == null) {
			return false;
		}
		
		double stepCost = node.stepCost(other);
		double slopeCost = node.slopeCost(other);
		if (stepCost <= GridGraph.maxStep) {
			valid = true;
		}
		

		if (valid) {
			double cost = GridGraph.baseConnectionCost;
			
			for (int i=0; i<GridGraph.slopePenalties.length;i+=2) {
				double max = GridGraph.slopePenalties[i];
				double penalty = GridGraph.slopePenalties[i+1];
				
				if (other.slope > max) {
					cost += penalty;
				}
			}
			
			for (int i=0; i<GridGraph.heightPenalties.length;i+=2) {
				double max = GridGraph.heightPenalties[i];
				double penalty = GridGraph.heightPenalties[i+1];
				
				if (other.position.z > max) {
					cost += penalty;
				}
			}
			
			GridConnection<Node> con = new GridConnection<Node>(node, other);
			con.cost = (float) cost;
			node.connections.add(con);
		}
		return valid;
	}

	public static NavmeshGraph importMesh() {
		TriangleMesh mesh = loadMesh();
		if (mesh == null) {
			return null;
		}
		return meshToGraph(mesh);
	}

	public static void stresstest(GridGraph graph, double startX, double startY, double endX, double endY,
			boolean smoothPath, boolean cover, int iterations) {
		for (int i = 1; i < iterations; i++) {
			graph.findPath(startX, startY, endX, endY, smoothPath, cover);
		}
	}

	public static void writePathData(PathData pathData) {
		byte[] bytes = pathData.toByteArray();
		File file = new File("/home/chris/game_machine/server/java/shared/pathdata.bin");
		try {
			Files.write(bytes, file);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static TriangleMesh loadMesh() {
		try {
			byte[] bytes = Files.toByteArray(new File("/home/chris/game_machine/server/mesh.bin"));
			return TriangleMesh.parseFrom(bytes);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	public static String VectortoString(Vector3 v) {
		return "(" + v.x + ", " + v.y + ", " + v.z + ")";
	}

	public static TriangleMesh flipTriangles(TriangleMesh mesh) {

		Integer[] array = new Integer[mesh.indices.size()];
		mesh.indices.toArray(array);

		for (int i = 0; i < mesh.indices.size() / 3; i++) {
			int t = array[i * 3];
			array[i * 3] = array[(i * 3) + 2];
			array[(i * 3) + 2] = t;
		}
		mesh.indices = Arrays.asList(array);
		return mesh;
	}

	/*
	 * Takes a triangle mesh and turns it into a graph that gdx-ai can work with
	 */
	public static NavmeshGraph meshToGraph(TriangleMesh mesh) {
		mesh = flipTriangles(mesh);
		HashMap<String, Node> nodes = new HashMap<String, Node>();
		NavmeshGraph graph = new NavmeshGraph();
		int index = 0;

		for (Vector3 vec : mesh.vertices) {
			Node node = new Node();
			node.position = new io.gamemachine.util.Vector3(vec.x, vec.y, vec.z);
			if (nodes.containsKey(node.position.toString())) {
				continue;
			}
			node.index = index;
			graph.nodes.put(node.index, node);
			nodes.put(node.position.toString(), node);
			graph.grid.set(node.index, node.position.x, node.position.y, node.position.z);
			index++;
		}

		int triangleCount = mesh.indices.size() / 3;
		Node c1;
		Node c2;
		Node c3;
		for (Node node : nodes.values()) {
			for (int i = 0; i < triangleCount; i++) {
				io.gamemachine.util.Vector3 v1 = fromVector3(mesh.vertices.get(mesh.indices.get(i * 3)));
				io.gamemachine.util.Vector3 v2 = fromVector3(mesh.vertices.get(mesh.indices.get(i * 3 + 1)));
				io.gamemachine.util.Vector3 v3 = fromVector3(mesh.vertices.get(mesh.indices.get(i * 3 + 2)));

				if (node.position.isEqualTo(v1)) {
					c2 = nodes.get(v2.toString());
					c3 = nodes.get(v3.toString());
					node.connections.add(new DefaultConnection<Node>(node, c2));
					node.connections.add(new DefaultConnection<Node>(node, c3));
				} else if (node.position.isEqualTo(v2)) {
					c1 = nodes.get(v1.toString());
					c3 = nodes.get(v3.toString());
					node.connections.add(new DefaultConnection<Node>(node, c1));
					node.connections.add(new DefaultConnection<Node>(node, c3));
				} else if (node.position.isEqualTo(v3)) {
					c1 = nodes.get(v1.toString());
					c2 = nodes.get(v2.toString());
					node.connections.add(new DefaultConnection<Node>(node, c1));
					node.connections.add(new DefaultConnection<Node>(node, c2));
				}
			}
		}
		return graph;
	}

	public static PathData fromPathResult(PathResult result) {
		PathData pathData = new PathData();
		pathData.startPoint = toVector3(result.startNode.position);
		pathData.endPoint = toVector3(result.endNode.position);

		if (result.smoothPath != null) {
			System.out.println("Exporting smooth path");
			for (io.gamemachine.util.Vector3 vec : result.smoothPath) {
				pathData.addNodes(toVector3(vec));
			}
		} else {
			for (int i = 0; i < result.resultPath.getCount(); i++) {
				Node node = (Node) result.resultPath.get(i);
				pathData.addNodes(toVector3(node.position));
			}
		}
		return pathData;
	}

	public static io.gamemachine.util.Vector3 fromVector3(Vector3 v) {
		return new io.gamemachine.util.Vector3(v.x, v.y, v.z);
	}

	public static Vector3 toVector3(io.gamemachine.util.Vector3 v1) {
		Vector3 v2 = new Vector3();
		v2.x = (float) v1.x;
		v2.y = (float) v1.z;
		v2.z = (float) v1.y;
		return v2;
	}

}
