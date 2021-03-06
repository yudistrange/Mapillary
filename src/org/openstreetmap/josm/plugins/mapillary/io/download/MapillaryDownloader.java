// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.mapillary.io.download;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.mapillary.MapillaryLayer;
import org.openstreetmap.josm.plugins.mapillary.utils.MapillaryProperties;
import org.openstreetmap.josm.tools.I18n;
import org.openstreetmap.josm.tools.Logging;

/**
 * Class that concentrates all the ways of downloading of the plugin. All the
 * download petitions will be managed one by one.
 *
 * @author nokutu
 */
public final class MapillaryDownloader {

  /** Possible download modes. */
  public enum DOWNLOAD_MODE {
    VISIBLE_AREA("visibleArea", I18n.tr("everything in the visible area")),
    OSM_AREA("osmArea", I18n.tr("areas with downloaded OSM-data")),
    MANUAL_ONLY("manualOnly", I18n.tr("only when manually requested"));

    private final String prefId;
    private final String label;

    DOWNLOAD_MODE(String prefId, String label) {
      this.prefId = prefId;
      this.label = label;
    }

    public String getPrefId() {
      return prefId;
    }

    public String getLabel() {
      return label;
    }

    public static DOWNLOAD_MODE fromPrefId(String prefId) {
      for (DOWNLOAD_MODE mode : DOWNLOAD_MODE.values()) {
        if (mode.getPrefId().equals(prefId)) {
          return mode;
        }
      }
      return getDefault();
    }

    public static DOWNLOAD_MODE fromLabel(String label) {
      for (DOWNLOAD_MODE mode : DOWNLOAD_MODE.values()) {
        if (mode.getLabel().equals(label)) {
          return mode;
        }
      }
      return getDefault();
    }

    public static DOWNLOAD_MODE getDefault() {
      return OSM_AREA;
    }
  }

  /** Max area to be downloaded */
  private static final double MAX_AREA = MapillaryProperties.MAX_DOWNLOAD_AREA.get();

  /** Executor that will run the petitions. */
  private static ThreadPoolExecutor executor = new ThreadPoolExecutor(
    3, 5, 100, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.DiscardPolicy());

  private MapillaryDownloader() {
    // Private constructor to avoid instantiation
  }

  /**
   * Gets all the images in a square. It downloads all the images of all the
   * sequences that pass through the given rectangle.
   *
   * @param minLatLon The minimum latitude and longitude of the rectangle.
   * @param maxLatLon The maximum latitude and longitude of the rectangle
   */
  public static void getImages(LatLon minLatLon, LatLon maxLatLon) {
    if (minLatLon == null || maxLatLon == null) {
      throw new IllegalArgumentException();
    }
    getImages(new Bounds(minLatLon, maxLatLon));
  }

  /**
   * Gets the images within the given bounds.
   *
   * @param bounds A {@link Bounds} object containing the area to be downloaded.
   */
  public static void getImages(Bounds bounds) {
    run(new MapillarySquareDownloadRunnable(bounds));
  }

  /**
   * Returns the current download mode.
   *
   * @return the currently enabled {@link DOWNLOAD_MODE}
   */
  public static DOWNLOAD_MODE getMode() {
    return MapillaryLayer.hasInstance() && MapillaryLayer.getInstance().tempSemiautomatic
      ? DOWNLOAD_MODE.VISIBLE_AREA
      : DOWNLOAD_MODE.fromPrefId(MapillaryProperties.DOWNLOAD_MODE.get());
  }

  private static void run(Runnable t) {
    executor.execute(t);
  }

  /**
   * If some part of the current view has not been downloaded, it is downloaded.
   */
  public static void downloadVisibleArea() {
    Bounds view = MainApplication.getMap().mapView.getRealBounds();
    if (view.getArea() > MAX_AREA) {
      return;
    }
    if (isViewDownloaded(view)) {
      return;
    }
    MapillaryLayer.getInstance().getData().getBounds().add(view);
    getImages(view);
  }

  private static boolean isViewDownloaded(Bounds view) {
    int n = 15;
    boolean[][] inside = new boolean[n][n];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        if (isInBounds(new LatLon(view.getMinLat()
          + (view.getMaxLat() - view.getMinLat()) * ((double) i / n),
          view.getMinLon() + (view.getMaxLon() - view.getMinLon())
            * ((double) j / n)))) {
          inside[i][j] = true;
        }
      }
    }
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        if (!inside[i][j])
          return false;
      }
    }
    return true;
  }

  /**
   * Checks if the given {@link LatLon} object lies inside the bounds of the
   * image.
   *
   * @param latlon The coordinates to check.
   *
   * @return true if it lies inside the bounds; false otherwise;
   */
  private static boolean isInBounds(LatLon latlon) {
    return MapillaryLayer.getInstance().getData().getBounds().parallelStream().anyMatch(b -> b.contains(latlon));
  }

  /**
   * Downloads all images of the area covered by the OSM data.
   */
  public static void downloadOSMArea() {
    if (MainApplication.getLayerManager().getEditLayer() == null) {
      return;
    }
    if (isOSMAreaTooBig()) {
      showOSMAreaTooBigErrorDialog();
      return;
    }
    MainApplication.getLayerManager().getEditLayer().data.getDataSourceBounds().stream().filter(bounds -> !MapillaryLayer.getInstance().getData().getBounds().contains(bounds)).forEach(bounds -> {
      MapillaryLayer.getInstance().getData().getBounds().add(bounds);
      MapillaryDownloader.getImages(bounds.getMin(), bounds.getMax());
    });
  }

  /**
   * Checks if the area of the OSM data is too big. This means that probably
   * lots of Mapillary images are going to be downloaded, slowing down the
   * program too much. To solve this the automatic is stopped, an alert is shown
   * and you will have to download areas manually.
   */
  private static boolean isOSMAreaTooBig() {
    double area = MainApplication.getLayerManager().getEditLayer().data.getDataSourceBounds().parallelStream().map(Bounds::getArea).reduce(0.0, Double::sum);
    return area > MAX_AREA;
  }

  private static void showOSMAreaTooBigErrorDialog() {
    if (SwingUtilities.isEventDispatchThread()) {
      MapillaryLayer.getInstance().tempSemiautomatic = true;
      JOptionPane
        .showMessageDialog(
          Main.parent,
          I18n.tr("The downloaded OSM area is too big. Download mode has been changed to OSM area until the layer is restarted."));
    } else {
      SwingUtilities.invokeLater(MapillaryDownloader::showOSMAreaTooBigErrorDialog);
    }
  }

  /**
   * Stops all running threads.
   */
  public static void stopAll() {
    executor.shutdownNow();
    try {
      executor.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Logging.error(e);
    }
    executor = new ThreadPoolExecutor(3, 5, 100, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(100));
  }
}
