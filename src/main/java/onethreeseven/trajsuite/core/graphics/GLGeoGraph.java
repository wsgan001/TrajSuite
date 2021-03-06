package onethreeseven.trajsuite.core.graphics;


import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.globes.Earth;
import gov.nasa.worldwind.layers.LayerList;
import gov.nasa.worldwind.render.DrawContext;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import onethreeseven.geo.model.LatLonBounds;
import onethreeseven.trajsuite.core.model.GeographicGraph;
import onethreeseven.trajsuite.core.util.WWExtrasUtil;
import onethreeseven.trajsuitePlugin.graphics.GraphicsPayload;
import onethreeseven.trajsuitePlugin.graphics.PackedVertexData;

import java.awt.*;
import java.beans.PropertyChangeListener;
import java.nio.DoubleBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * The graphical representation of the graphhopper graph.
 * @author Luke Bermingham
 */

public class GLGeoGraph extends GLVboRenderable {


    private final AtomicReference<PackedVertexData> payload = new AtomicReference<>(null);
    private boolean listeningForChanges = false;
    private PropertyChangeListener viewChangeListener = null;

    private final ThreadFactory tf = r -> new Thread(r, "GLGeoGraph");
    private final ExecutorService exec = Executors.newSingleThreadExecutor(tf);


    private ObjectProperty<LatLonBounds> viewBounds = new SimpleObjectProperty<>(
            new LatLonBounds(-85, 85, -175, 175));

    private boolean firstDraw = true;
    private CompletableFuture jobStatus = null;
    private AtomicBoolean isDirty = new AtomicBoolean(true);
    private double movementThreshold = 1000;

    public GLGeoGraph(GeographicGraph model, GraphicsPayload graphicsPayload, LayerList ll) {
        super(model, graphicsPayload, ll);

        graphicsPayload.fallbackColor.setValue(new Color(0, 128, 128));

        //when view bounds change, update the graph verts
        this.viewBounds.addListener((observable, oldValue, updatedSector) -> {
            LatLonBounds graphSector = getGraph().getSector();
            //if the graph is within view
            if (updatedSector.contains(graphSector) || updatedSector.intersects(graphSector)) {
                updateVertsViaWorker();
            }
        });

    }

//    @Override
//    protected PackedVertexData createVertexData() {
//        PackedVertexData cached = payload.get();
//        if (cached != null) {
//            payload.set(null);
//            return cached;
//        } else {
//            return createVertexDataImpl();
//        }
//    }

    private PackedVertexData createVertexDataImpl() {
        if (!firstDraw) {
            GeographicGraph graph = getGraph();

            DoubleBuffer buf = graph.getEdges(viewBounds.get());

            return new PackedVertexData(buf,
                    new PackedVertexData.Types[]{PackedVertexData.Types.VERTEX});
        }else{
            double[] pts = new double[]{0, 0, 0, 1, 1, 1};
            firstDraw = false;
            return new PackedVertexData(pts,
                    new PackedVertexData.Types[]{PackedVertexData.Types.VERTEX});
        }
    }

    private GeographicGraph getGraph() {
        return (GeographicGraph) this.model;
    }

    private void updateVertsViaWorker() {
        if (jobStatus == null || jobStatus.isDone() || jobStatus.isCancelled()) {
            jobStatus = CompletableFuture.runAsync(()->{

                ////////////////    |
                //THE WORK          |
                ///////////////     V

                //Start requesting graph edges
                payload.set(createVertexDataImpl());

                //now handle success and failure cases below

            }, exec).handle((aVoid, throwable) -> {
                //success
                if(throwable == null){
                    //Finished requesting graph edges
                    //if view has changed since we were working, update
                    if (isDirty.get()) {
                        updateVertsViaWorker();
                    }
                }
                //failure
                else{
                    throwable.printStackTrace();
                }
                return null;
            });
            isDirty.set(false);

        } else {
            //job is still executing
            isDirty.set(true);
        }

    }

    @Override
    protected void beginDrawing(DrawContext dc) {
        super.beginDrawing(dc);
        //if we are not listening for view changes, start
        if (!listeningForChanges) {
            //init view change listener
            this.viewChangeListener = evt -> {
                movementThreshold = dc.getView().getEyePosition().getAltitude() / 100.0;
                LatLonBounds visibleSector = WWExtrasUtil.getVisibleSector(dc.getView());
                if (hasSectorMovedEnough(visibleSector, viewBounds.get())) {
                    viewBounds.setValue(visibleSector);
                }
            };
            dc.getView().addPropertyChangeListener(AVKey.VIEW, viewChangeListener);
            listeningForChanges = true;
        }
        //if there is a payload, refresh the vbo
        if (payload.get() != null) {
            getPayload().isDirty.set(true);
        }
    }

    @Override
    public void drawEntity(DrawContext dc) {
        dc.getGL().glLineWidth(1);
        super.drawEntity(dc);
    }

    @Override
    public void render(DrawContext dc) {
        super.render(dc);
    }

//    @Override
//    protected int getRenderingMode() {
//        return GL.GL_LINES;
//    }

    @Override
    public void cleanup(DrawContext dc) {
        super.cleanup(dc);
        dc.getView().removePropertyChangeListener(AVKey.VIEW, viewChangeListener);
    }

    private boolean hasSectorMovedEnough(LatLonBounds oldSector, LatLonBounds newSector) {
        LatLon oldCorner = LatLon.fromDegrees(oldSector.getMinLat(), oldSector.getMinLon());
        LatLon newCorner = LatLon.fromDegrees(newSector.getMinLat(), newSector.getMinLon());
        double distance = LatLon.ellipsoidalDistance(oldCorner, newCorner, Earth.WGS84_EQUATORIAL_RADIUS, Earth.WGS84_POLAR_RADIUS);
        return distance > movementThreshold;
    }

}
