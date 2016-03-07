/*******************************************************************************
 * Copyright (c) 2014, 2015 itemis AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Matthias Wienand (itemis AG) - initial API and implementation
 *
 *******************************************************************************/
package org.eclipse.gef4.mvc.fx.policies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.operations.IUndoableOperation;
import org.eclipse.gef4.fx.anchors.IAnchor;
import org.eclipse.gef4.fx.anchors.StaticAnchor;
import org.eclipse.gef4.fx.nodes.Connection;
import org.eclipse.gef4.fx.nodes.OrthogonalRouter;
import org.eclipse.gef4.fx.utils.NodeUtils;
import org.eclipse.gef4.geometry.convert.fx.FX2Geometry;
import org.eclipse.gef4.geometry.convert.fx.Geometry2FX;
import org.eclipse.gef4.geometry.internal.utils.PrecisionUtils;
import org.eclipse.gef4.geometry.planar.Dimension;
import org.eclipse.gef4.geometry.planar.Line;
import org.eclipse.gef4.geometry.planar.Point;
import org.eclipse.gef4.mvc.fx.operations.FXBendOperation;
import org.eclipse.gef4.mvc.fx.parts.FXPartUtils;
import org.eclipse.gef4.mvc.models.GridModel;
import org.eclipse.gef4.mvc.operations.DeselectOperation;
import org.eclipse.gef4.mvc.operations.ForwardUndoCompositeOperation;
import org.eclipse.gef4.mvc.operations.ITransactionalOperation;
import org.eclipse.gef4.mvc.operations.ReverseUndoCompositeOperation;
import org.eclipse.gef4.mvc.operations.SelectOperation;
import org.eclipse.gef4.mvc.operations.SetRefreshVisualOperation;
import org.eclipse.gef4.mvc.parts.IContentPart;
import org.eclipse.gef4.mvc.parts.IVisualPart;
import org.eclipse.gef4.mvc.policies.AbstractTransactionPolicy;
import org.eclipse.gef4.mvc.policies.AbstractTransformPolicy;
import org.eclipse.gef4.mvc.viewer.IViewer;

import com.google.common.reflect.TypeToken;
import com.google.inject.Provider;

import javafx.scene.Node;

/**
 * The {@link FXBendConnectionPolicy} can be used to manipulate the points
 * constituting an {@link Connection}, i.e. its start, way, and end points. Each
 * point is realized though an {@link IAnchor}, which may either be local to the
 * {@link Connection} (i.e. the anchor refers to the {@link Connection} as
 * anchorage), or it may be provided by another {@link IVisualPart} (i.e. the
 * anchor is provided by a {@link Provider} adapted to the part), to which the
 * connection is being connected.
 *
 * When moving a point the policy takes care of:
 * <ul>
 * <li>Removing overlaid neighbor points.</li>
 * <li>Re-adding temporarily removed neighbor points.</li>
 * <li>Reconnecting points to the {@link IVisualPart} under mouse when
 * applicable.</li>
 * </ul>
 *
 * @author mwienand
 * @author anyssen
 */
public class FXBendConnectionPolicy extends AbstractTransactionPolicy<Node> {

	/**
	 * The overlay threshold, i.e. the distance between two points so that they
	 * are regarded as overlying.
	 */
	protected static final double DEFAULT_OVERLAY_THRESHOLD = 10;

	// selection index => removed anchor
	private Map<Integer, IAnchor> removedOverlainAnchors = new HashMap<>();
	// selection index => overlain index
	private Map<Integer, Integer> removedOverlainAnchorsIndices = new HashMap<>();
	// selection index => segment index
	private List<Integer> selectedPointsIndices = new ArrayList<>();
	// selection index => initial position
	private List<Point> selectedPointsInitialPositionsInLocal = new ArrayList<>();
	// selection index => previous index
	private Map<Integer, Integer> selectedPointsIndicesBeforeOverlaidRemoval = new HashMap<>();

	private Point initialMousePositionInScene;

	/**
	 * Returns <code>true</code> if the currently modified start, end, or way
	 * point can be connected, i.e. realized by an anchor that is not anchored
	 * to the {@link Connection} itself (see {@link IAnchor#getAnchorage()} ),
	 * but provided through a {@link IVisualPart}'s anchor provider (i.e. a
	 * {@link Provider}&lt;{@link IAnchor}&gt; adapted to the
	 * {@link IVisualPart}). Otherwise returns <code>false</code>. Per default,
	 * only the start and the end point can be attached.
	 *
	 * @param pointIndex
	 *            The index of the currently modified connection point.
	 *
	 * @return <code>true</code> if the currently modified point can be realized
	 *         through an {@link IAnchor} not anchored on the {@link Connection}
	 *         . Otherwise returns <code>false</code>.
	 *
	 * @see Connection#isStartConnected()
	 * @see Connection#isControlConnected(int)
	 * @see Connection#isEndConnected()
	 *
	 */
	protected boolean canConnect(int pointIndex) {
		// up to now, only allow attaching start and end point.
		return pointIndex == 0
				|| pointIndex == getBendOperation().getNewAnchors().size() - 1;
	}

	@Override
	public ITransactionalOperation commit() {
		ITransactionalOperation bendOperation = super.commit();

		if (bendOperation == null || bendOperation.isNoOp()) {
			return null;
		}

		// chain a reselect operation here, so handles are properly updated
		// TODO: check if we cannot handle this otherwise
		ForwardUndoCompositeOperation updateOperation = new ForwardUndoCompositeOperation(
				bendOperation.getLabel());
		updateOperation.add(bendOperation);
		updateOperation.add(createReselectOperation());

		// guard the update operation from refreshes
		// TODO: check if this is needed (it should actually be performed by
		// the interaction policy)
		ReverseUndoCompositeOperation commit = new ReverseUndoCompositeOperation(
				bendOperation.getLabel());
		commit.add(new SetRefreshVisualOperation<>(getHost(),
				getHost().isRefreshVisual(), false));
		commit.add(updateOperation);
		commit.add(new SetRefreshVisualOperation<>(getHost(), false,
				getHost().isRefreshVisual()));

		return commit;
	}

	/**
	 * Copies the specified point and selects it for further manipulation.
	 *
	 * @param segmentIndex
	 *            The index of the manipulated segment.
	 * @param segmentParameter
	 *            The parameter describing the point on the specified segment.
	 * @param mouseInScene
	 *            The current mouse pointer location.
	 */
	public void copyAndSelectPoint(int segmentIndex, double segmentParameter,
			Point mouseInScene) {
		checkInitialized();

		// determine anchor index
		int anchorIndex, insertionIndex;
		if (segmentParameter == 1) {
			anchorIndex = segmentIndex + 1;
			insertionIndex = anchorIndex;
		} else {
			anchorIndex = segmentIndex;
			insertionIndex = anchorIndex + 1;
		}

		// copy anchor at that index
		List<IAnchor> newAnchors = getBendOperation().getNewAnchors();
		IAnchor anchorToCopy = newAnchors.get(anchorIndex);
		Point position = getConnection().getPoints().get(anchorIndex);
		boolean canConnect = anchorToCopy.getAnchorage() != getConnection();
		IAnchor copy = findOrCreateAnchor(position, canConnect);
		newAnchors.add(insertionIndex, copy);

		// execute locally to add the new anchor
		locallyExecuteOperation();

		// select the copied way point
		// XXX: Segment parameter is always 0 because the copied anchor may
		// never be the last point (except after overlay removal)
		selectPoint(insertionIndex, 0, mouseInScene);
	}

	/**
	 * Creates a new point at the given segment. The new way point is then
	 * selected for further manipulation.
	 *
	 * @param segmentIndex
	 *            The index of the segment for which a new way point is created.
	 * @param mouseInScene
	 *            The mouse position in scene coordinates.
	 */
	public void createAndSelectPoint(int segmentIndex, Point mouseInScene) {
		checkInitialized();

		// create new way point
		Point mouseInLocal = FX2Geometry.toPoint(getConnection()
				.sceneToLocal(Geometry2FX.toFXPoint(mouseInScene)));
		getBendOperation().getNewAnchors().add(segmentIndex + 1,
				createUnconnectedAnchor(mouseInLocal));

		locallyExecuteOperation();

		// select newly created way point
		selectPoint(segmentIndex + 1, 0, mouseInScene);
	}

	@Override
	protected FXBendOperation createOperation() {
		return new FXBendOperation(getConnection());
	}

	/**
	 * Create an {@link IUndoableOperation} to re-select the host part.
	 *
	 * @return An {@link IUndoableOperation} that deselects and selects the root
	 *         part.
	 */
	protected ReverseUndoCompositeOperation createReselectOperation() {
		if (!(getHost() instanceof IContentPart)) {
			return null;
		}

		// assemble deselect and select operations to form a reselect
		ReverseUndoCompositeOperation reselectOperation = new ReverseUndoCompositeOperation(
				"re-select");

		// build "deselect host" operation
		IViewer<Node> viewer = getHost().getRoot().getViewer();
		DeselectOperation<Node> deselectOperation = new DeselectOperation<>(
				viewer, Collections.singletonList(
						(IContentPart<Node, Connection>) getHost()));

		// build "select host" operation
		SelectOperation<Node> selectOperation = new SelectOperation<>(viewer,
				Collections.singletonList(
						(IContentPart<Node, Connection>) getHost()));

		reselectOperation.add(deselectOperation);
		reselectOperation.add(selectOperation);
		return reselectOperation;

	}

	/**
	 * Creates an (unconnected) anchor (i.e. one anchored on the
	 * {@link Connection}) for the given position (in scene coordinates).
	 *
	 * @param selectedPointCurrentPositionInLocal
	 *            The location in local coordinates of the connection
	 * @return An {@link IAnchor} that yields the given position.
	 */
	protected IAnchor createUnconnectedAnchor(
			Point selectedPointCurrentPositionInLocal) {
		return new StaticAnchor(getConnection(),
				selectedPointCurrentPositionInLocal);
	}

	/**
	 * Determines the {@link IAnchor} that should replace the anchor of the
	 * currently selected point. If the point can connect, the
	 * {@link IVisualPart} at the mouse position is queried for an
	 * {@link IAnchor} via a {@link Provider}&lt;{@link IAnchor}&gt; adapter.
	 * Otherwise an (unconnected) anchor is create using
	 * {@link #createUnconnectedAnchor(Point)} .
	 *
	 * @param positionInLocal
	 *            A position in local coordinates of the connection.
	 * @param canConnect
	 *            <code>true</code> if the point can be attached to an
	 *            underlying {@link IVisualPart}, otherwise <code>false</code>.
	 * @return The {@link IAnchor} that replaces the anchor of the currently
	 *         modified point.
	 */
	@SuppressWarnings("serial")
	protected IAnchor findOrCreateAnchor(Point positionInLocal,
			boolean canConnect) {
		IAnchor anchor = null;
		// try to find an anchor that is provided from an underlying node
		if (canConnect) {
			Point selectedPointCurrentPositionInScene = FX2Geometry
					.toPoint(getConnection().localToScene(
							Geometry2FX.toFXPoint(positionInLocal)));
			List<Node> pickedNodes = NodeUtils.getNodesAt(
					getHost().getRoot().getVisual(),
					selectedPointCurrentPositionInScene.x,
					selectedPointCurrentPositionInScene.y);
			IVisualPart<Node, ? extends Node> anchorPart = getAnchorPart(
					getParts(pickedNodes));
			if (anchorPart != null) {
				// use anchor returned by part
				anchor = anchorPart.getAdapter(
						new TypeToken<Provider<? extends IAnchor>>() {
						}).get();
			}
		}
		if (anchor == null) {
			anchor = createUnconnectedAnchor(positionInLocal);
		}
		return anchor;
	}

	@SuppressWarnings("serial")
	private IContentPart<Node, ? extends Node> getAnchorPart(
			List<IContentPart<Node, ? extends Node>> partsUnderMouse) {
		for (IContentPart<Node, ? extends Node> cp : partsUnderMouse) {
			IContentPart<Node, ? extends Node> part = cp;
			Provider<? extends IAnchor> anchorProvider = part
					.getAdapter(new TypeToken<Provider<? extends IAnchor>>() {
					});
			if (anchorProvider != null && anchorProvider.get() != null) {
				return part;
			}
		}
		return null;
	}

	/**
	 * Returns an {@link FXBendOperation} that is extracted from the operation
	 * created by {@link #createOperation()}.
	 *
	 * @return an {@link FXBendOperation} that is extracted from the operation
	 *         created by {@link #createOperation()}.
	 */
	protected FXBendOperation getBendOperation() {
		return (FXBendOperation) super.getOperation();
	}

	/**
	 * Returns the {@link Connection} that is manipulated by this policy.
	 *
	 * @return The {@link Connection} that is manipulated by this policy.
	 */
	protected Connection getConnection() {
		return getHost().getVisual();
	}

	@SuppressWarnings("unchecked")
	@Override
	public IVisualPart<Node, Connection> getHost() {
		return (IVisualPart<Node, Connection>) super.getHost();
	}

	/**
	 * Computes the mouse movement delta (w.r.t. to the initial mouse position)
	 * in local coordinates .
	 *
	 * @param currentMousePositionInScene
	 *            The current mouse position in scene coordinates.
	 * @return The movement delta, translated into local coordinates of the
	 *         connection
	 *
	 */
	// TODO: extract to somewhere else (this is used in several places)
	protected Point getMouseDeltaInLocal(Point currentMousePositionInScene) {
		Point mouseInLocal = FX2Geometry.toPoint(getConnection().sceneToLocal(
				Geometry2FX.toFXPoint(currentMousePositionInScene)));
		// compensate the movement of the local coordinate system w.r.t. the
		// scene coordinate system (the scene coordinate system stays consistent
		// w.r.t. to mouse movement)
		Point deltaInLocal = mouseInLocal
				.getTranslated(FX2Geometry
						.toPoint(getConnection().sceneToLocal(Geometry2FX
								.toFXPoint(initialMousePositionInScene)))
				.getNegated());
		return deltaInLocal;
	}

	/**
	 * If the point at the given index is overlain by the currently selected
	 * point, i.e. their distance is smaller than the
	 * {@link #getOverlayThreshold() overlay threshold} and they are above the
	 * same anchorage, returns the anchor that can be found at the candidate
	 * location.
	 *
	 * @param selectionIndex
	 *            The index of the selected point.
	 *
	 * @param candidateIndex
	 *            The candidate index.
	 * @param mouseInScene
	 *            The current mouse position in scene coordinates.
	 *
	 * @return The overlaid {@link IAnchor} to be used for the currently
	 *         selected point
	 */
	protected IAnchor getOverlayAnchor(int selectionIndex, int candidateIndex,
			Point mouseInScene) {
		Connection connection = getConnection();
		Point candidateLocation = connection.getPoint(candidateIndex);

		// overlay if distance is small enough and we do not change the
		// anchorage
		Point selectedPointCurrentPositionInLocal = selectedPointsInitialPositionsInLocal
				.get(selectionIndex)
				.getTranslated(getMouseDeltaInLocal(mouseInScene));
		if (candidateLocation.getDistance(
				selectedPointCurrentPositionInLocal) >= getOverlayThreshold()) {
			return null;
		}

		IAnchor candidateAnchor = findOrCreateAnchor(
				selectedPointCurrentPositionInLocal, true);
		if (connection.getAnchors().get(candidateIndex)
				.getAnchorage() == candidateAnchor.getAnchorage()) {
			return connection.getAnchors().get(candidateIndex);
		}
		return null;
	}

	/**
	 * Removes the overlay threshold, i.e. the distance between two points, so
	 * that they are regarded as overlaying. When the background grid is enables
	 * ( {@link GridModel#isShowGrid()}, then the grid cell size is used to
	 * determine the overlay threshold. Otherwise, the
	 * {@link #DEFAULT_OVERLAY_THRESHOLD} is used.
	 *
	 * @return The overlay threshold.
	 */
	protected double getOverlayThreshold() {
		GridModel model = getHost().getRoot().getViewer()
				.getAdapter(GridModel.class);
		if (model != null && model.isSnapToGrid()) {
			return Math.min(model.getGridCellWidth(), model.getGridCellHeight())
					/ 4;
		}
		return DEFAULT_OVERLAY_THRESHOLD;
	}

	private List<IContentPart<Node, ? extends Node>> getParts(
			List<Node> nodesUnderMouse) {
		List<IContentPart<Node, ? extends Node>> parts = new ArrayList<>();

		IViewer<Node> viewer = getHost().getRoot().getViewer();
		for (Node node : nodesUnderMouse) {
			IVisualPart<Node, ? extends Node> part = FXPartUtils
					.retrieveVisualPart(viewer, node);
			if (part instanceof IContentPart) {
				parts.add((IContentPart<Node, ? extends Node>) part);
			}
		}
		return parts;
	}

	/**
	 * Returns the initial position of the currently selected point in the local
	 * coordinate system of the {@link Connection}.
	 *
	 * @param selectionIndex
	 *            The index of the selected point.
	 *
	 * @return The initial position in the local coordinate system of the
	 *         {@link Connection}.
	 *
	 * @see #selectPoint(int, double, Point)
	 */
	protected Point getSelectedPointInitialPositionInLocal(int selectionIndex) {
		return selectedPointsInitialPositionsInLocal.get(selectionIndex);
	}

	private int getStaticAnchorIndex(List<IAnchor> newAnchors, int i) {
		for (int j = i; j < newAnchors.size(); j++) {
			if (newAnchors.get(j) instanceof StaticAnchor) {
				return j;
			}
		}
		return -1;
	}

	/**
	 * Handles the hiding of an overlain point as well as the expose of a
	 * previously overlain point.
	 * <ol>
	 * <li>Restores a point that was previously removed because it was overlaid.
	 * </li>
	 * <li>Checks if the currently modified point overlays another point of the
	 * {@link Connection}. The overlaid point is removed and saved so that it
	 * can be restored later.</li>
	 * </ol>
	 *
	 * @param mouseInScene
	 *            The current mouse position in scene coordinates.
	 */
	protected void handleOverlay(Point mouseInScene) {
		// put removed back in (may be removed againg before returning)
		for (int index = 0; index < selectedPointsIndices.size(); index++) {
			IAnchor removedOverlainAnchor = removedOverlainAnchors.get(index);
			if (removedOverlainAnchor != null) {
				// add anchor
				getBendOperation().getNewAnchors().add(
						removedOverlainAnchorsIndices.get(index),
						removedOverlainAnchor);
				// reset point index
				selectedPointsIndices.set(index,
						selectedPointsIndicesBeforeOverlaidRemoval.get(index));
				// increment subsequent indices
				for (int i = index + 1; i < selectedPointsIndices.size(); i++) {
					selectedPointsIndices.set(i,
							selectedPointsIndices.get(i) + 1);
					if (selectedPointsIndicesBeforeOverlaidRemoval
							.containsKey(i)) {
						selectedPointsIndicesBeforeOverlaidRemoval.put(i,
								selectedPointsIndicesBeforeOverlaidRemoval
										.get(i) + 1);
					}
					if (removedOverlainAnchorsIndices.containsKey(i)) {
						removedOverlainAnchorsIndices.put(i,
								removedOverlainAnchorsIndices.get(i) + 1);
					}
				}
				// execute
				locallyExecuteOperation();
				removedOverlainAnchors.remove(index);
			}
		}

		// do not remove overlaid if there are no way points
		if (getBendOperation().getNewAnchors().size() <= 2) {
			return;
		}

		// remove overlain anchors
		for (int index = 0; index < selectedPointsIndices.size(); index++) {
			removedOverlainAnchorsIndices.put(index, -1);
			Integer selectedPointIndex = selectedPointsIndices.get(index);
			selectedPointsIndicesBeforeOverlaidRemoval.put(index,
					selectedPointIndex);
			IAnchor overlayAnchor = null;

			// determine if left neighbor is overlain (and can be removed)
			if (selectedPointIndex > 0) {
				int candidateIndex = selectedPointIndex - 1;
				overlayAnchor = getOverlayAnchor(index, candidateIndex,
						mouseInScene);
				if (overlayAnchor != null) {
					// remove previous (in case of start point, ensure we stay
					// anchored to the same anchorage)
					removedOverlainAnchorsIndices.put(index, candidateIndex);
					selectedPointIndex--;
					selectedPointsIndices.set(index, selectedPointIndex);
				}
			}

			// if left neighbor is not overlain (and not removed), determine if
			// right neighbor is overlain (and can be removed)
			if (removedOverlainAnchorsIndices.get(index) == -1
					&& selectedPointIndex < getBendOperation().getNewAnchors()
							.size() - 1) {
				int candidateIndex = selectedPointIndex + 1;
				overlayAnchor = getOverlayAnchor(index, candidateIndex,
						mouseInScene);
				if (overlayAnchor != null) {
					// remove next (in case of end point, ensure we stay
					// anchored to the same anchorage)
					removedOverlainAnchorsIndices.put(index, candidateIndex);
				}
			}

			// remove neighbor if overlaid
			if (removedOverlainAnchorsIndices.get(index) != -1) {
				// decrement subsequent indices
				for (int i = index + 1; i < selectedPointsIndices.size(); i++) {
					selectedPointsIndices.set(i,
							selectedPointsIndices.get(i) - 1);
					if (selectedPointsIndicesBeforeOverlaidRemoval
							.containsKey(i)) {
						selectedPointsIndicesBeforeOverlaidRemoval.put(i,
								selectedPointsIndicesBeforeOverlaidRemoval
										.get(i) - 1);
					}
					if (removedOverlainAnchorsIndices.containsKey(i)) {
						removedOverlainAnchorsIndices.put(i,
								removedOverlainAnchorsIndices.get(i) - 1);
					}
				}
				// replace anchor
				getBendOperation().getNewAnchors().set(
						selectedPointsIndicesBeforeOverlaidRemoval.get(index),
						overlayAnchor);
				// save overlain anchor
				removedOverlainAnchors.put(index,
						getBendOperation().getNewAnchors()
								.remove((int) removedOverlainAnchorsIndices
										.get(index)));
				// execute
				locallyExecuteOperation();
			}
		}
	}

	@Override
	public void init() {
		removedOverlainAnchors.clear();
		removedOverlainAnchorsIndices.clear();
		selectedPointsIndices.clear();
		selectedPointsIndicesBeforeOverlaidRemoval.clear();
		selectedPointsInitialPositionsInLocal.clear();
		super.init();
	}

	@Override
	protected void locallyExecuteOperation() {
		// XXX: For segment based connections, the control points need to be
		// normalized, i.e. all control points that lie on the orthogonal
		// connection between two other control points have to be removed.
		if (getConnection().getRouter() instanceof OrthogonalRouter) {
			FXBendOperation bendOperation = getBendOperation();
			List<IAnchor> newAnchors = bendOperation.getNewAnchors();

			// find first static anchor
			int prevIndex = getStaticAnchorIndex(newAnchors, 0);
			if (prevIndex >= 0) {
				List<Integer> indicesToRemove = new ArrayList<>();
				int currentIndex = getStaticAnchorIndex(newAnchors,
						prevIndex + 1);
				int nextIndex = getStaticAnchorIndex(newAnchors,
						currentIndex + 1);
				while (currentIndex >= 0 && nextIndex >= 0) {
					Point prev = ((StaticAnchor) newAnchors.get(prevIndex))
							.getReferencePosition();
					Point current = ((StaticAnchor) newAnchors
							.get(currentIndex)).getReferencePosition();
					Point next = ((StaticAnchor) newAnchors.get(nextIndex))
							.getReferencePosition();

					Line line = new Line(prev, next);
					if (line.contains(current)) {
						indicesToRemove.add(currentIndex);
						currentIndex = prevIndex;
					}

					prevIndex = currentIndex;
					currentIndex = nextIndex;
					nextIndex = getStaticAnchorIndex(newAnchors, nextIndex + 1);
				}
				for (int i = indicesToRemove.size() - 1; i >= 0; i--) {
					newAnchors.remove(indicesToRemove.get(i));
				}
			}
		}
		super.locallyExecuteOperation();
	}

	/**
	 * Moves the currently selected point to the given mouse position in scene
	 * coordinates. Checks if the selected point overlays another point using
	 * {@link #handleOverlay(Point)}.
	 *
	 * @param mouseInScene
	 *            The current mouse position in scene coordinates.
	 */
	public void moveSelectedPoints(Point mouseInScene) {
		checkInitialized();
		if (selectedPointsIndices.isEmpty()) {
			throw new IllegalStateException("No point was selected.");
		}

		// constrain movement in one direction for segment based connections
		int numPoints = selectedPointsInitialPositionsInLocal.size();
		boolean isSegmentBased = numPoints > 1
				&& getConnection().getRouter() instanceof OrthogonalRouter;
		Point mouseDeltaInLocal = getMouseDeltaInLocal(mouseInScene);
		if (isSegmentBased) {
			boolean isHorizontallyConstrained = PrecisionUtils.equal(
					selectedPointsInitialPositionsInLocal.get(0).y,
					selectedPointsInitialPositionsInLocal.get(1).y);
			if (isHorizontallyConstrained) {
				mouseDeltaInLocal.x = 0;
			} else {
				mouseDeltaInLocal.y = 0;
			}
		}

		// update positions
		for (int i = 0; i < selectedPointsIndices.size(); i++) {
			Point selectedPointCurrentPositionInLocal = this.selectedPointsInitialPositionsInLocal
					.get(i).getTranslated(mouseDeltaInLocal);

			// snap-to-grid
			// TODO: make snapping (0.5) configurable
			Dimension snapToGridOffset = AbstractTransformPolicy
					.getSnapToGridOffset(
							getHost().getRoot().getViewer()
									.<GridModel> getAdapter(GridModel.class),
							selectedPointCurrentPositionInLocal.x,
							selectedPointCurrentPositionInLocal.y, 0.5, 0.5);
			selectedPointCurrentPositionInLocal
					.translate(snapToGridOffset.getNegated());

			getBendOperation().getNewAnchors().set(selectedPointsIndices.get(i),
					findOrCreateAnchor(selectedPointCurrentPositionInLocal,
							canConnect(selectedPointsIndices.get(i))));
		}

		locallyExecuteOperation();

		handleOverlay(mouseInScene);
		// System.out.println("selected points indices: " +
		// selectedPointsIndices);
		// System.out.println("selected points indices before overlay removal: "
		// + selectedPointsIndicesBeforeOverlaidRemoval);
		// System.out.println("removed overlain anchors indices: "
		// + removedOverlainAnchorsIndices);
		// System.out.println();
	}

	/**
	 * Selects the point specified by the given segment index and parameter for
	 * manipulation. Captures the initial position of the selected point (see
	 * {@link #getSelectedPointInitialPositionInLocal(int)}) and the related
	 * initial mouse location.
	 *
	 * @param segmentIndex
	 *            The index of the segment of which a point is to be
	 *            manipulated.
	 * @param segmentParameter
	 *            The parameter on the segment to identify if its the end point.
	 * @param mouseInScene
	 *            The current mouse position in scene coordinates.
	 */
	public void selectPoint(int segmentIndex, double segmentParameter,
			Point mouseInScene) {
		checkInitialized();

		// store handle part information
		if (segmentParameter == 1) {
			selectedPointsIndices.add(segmentIndex + 1);
		} else {
			selectedPointsIndices.add(segmentIndex);
		}

		initialMousePositionInScene = mouseInScene.getCopy();
		Integer index = selectedPointsIndices
				.get(selectedPointsIndices.size() - 1);
		Point position = getConnection().getPoints().get(index);
		selectedPointsInitialPositionsInLocal.add(position);

		// XXX: In case a router inserted control points, those have to be
		// converted to real static anchors when interacted with so that the
		// router is not allowed to remove them later.
		IAnchor anchor = getConnection().getAnchor(index);
		if (anchor instanceof StaticAnchor) {
			getBendOperation().getNewAnchors().set(index,
					createUnconnectedAnchor(position));
		}
	}

	@Override
	public String toString() {
		return "FXBendConnectionPolicy[host=" + getHost() + "]";
	}

}