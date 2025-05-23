package gg.xp.xivsupport.gui.map;

import gg.xp.xivdata.data.*;
import gg.xp.xivsupport.events.actlines.events.TetherEvent;
import gg.xp.xivsupport.events.state.combatstate.CastTracker;
import gg.xp.xivsupport.events.state.floormarkers.FloorMarker;
import gg.xp.xivsupport.events.triggers.jobs.gui.CastBarComponent;
import gg.xp.xivsupport.gui.map.omen.ActionOmenInfo;
import gg.xp.xivsupport.gui.map.omen.OmenDisplayMode;
import gg.xp.xivsupport.gui.map.omen.OmenInstance;
import gg.xp.xivsupport.gui.overlay.RefreshLoop;
import gg.xp.xivsupport.gui.tables.renderers.HpBar;
import gg.xp.xivsupport.gui.tables.renderers.IconTextRenderer;
import gg.xp.xivsupport.gui.tables.renderers.OverlapLayout;
import gg.xp.xivsupport.gui.tables.renderers.RenderUtils;
import gg.xp.xivsupport.gui.tables.renderers.ScaledImageComponent;
import gg.xp.xivsupport.models.CombatantType;
import gg.xp.xivsupport.models.HitPoints;
import gg.xp.xivsupport.models.Position;
import gg.xp.xivsupport.models.XivCombatant;
import gg.xp.xivsupport.models.XivPlayerCharacter;
import gg.xp.xivsupport.persistence.settings.BooleanSetting;
import gg.xp.xivsupport.persistence.settings.EnumSetting;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.io.Serial;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public class MapPanel extends JPanel implements MouseMotionListener, MouseListener, MouseWheelListener {

	private static final Logger log = LoggerFactory.getLogger(MapPanel.class);
	@Serial
	private static final long serialVersionUID = 6804697839463860552L;

	private final Map<Long, EntityDoohickey> things = new HashMap<>();
	private final RefreshLoop<MapPanel> refresher;
	private final MapDataController mdc;
	private final EnumSetting<NameDisplayMode> nameDisp;
	private final EnumSetting<OmenDisplayMode> omenDisp;
	private final BooleanSetting displayHpBars;
	private final BooleanSetting displayCastBars;
	private final BooleanSetting displayIds;
	private final BooleanSetting displayTethers;
	private final BooleanSetting displayHitbox;
	private final MapColorSettings colorSettings;
	private double zoomFactor = 1;
	private volatile int curXpan;
	private volatile int curYpan;
	private volatile Point dragPoint;
	private XivMap map = XivMap.UNKNOWN;
	private Image backgroundImage;
	// -1 indicates no selection
	private volatile long selection = -1;
	private Consumer<@Nullable XivCombatant> selectionCallback = l -> {
	};
	private Map<FloorMarker, FloorMarkerDoohickey> markers;
	private boolean needZorderCheck;


	public MapPanel(MapDataController mdc, MapDisplayConfig mapDisplayConfig, MapColorSettings colorSettings) {
		this.mdc = mdc;
		omenDisp = mapDisplayConfig.getOmenDisplayMode();
		nameDisp = mapDisplayConfig.getNameDisplayMode();
		displayHpBars = mapDisplayConfig.getHpBars();
		displayCastBars = mapDisplayConfig.getCastBars();
		displayIds = mapDisplayConfig.getIds();
		displayTethers = mapDisplayConfig.getTethers();
		displayHitbox = mapDisplayConfig.getDisplayHitboxes();
		this.colorSettings = colorSettings;
		colorSettings.addListener(this::forceRefreshAllCbts);

		setLayout(null);
		setBackground(new Color(168, 153, 114));
		refresher = new RefreshLoop<>("MapRefresh", this, map -> {
			SwingUtilities.invokeLater(() -> {
				if (map.isShowing()) {
					requestRefresh();
				}
			});
		}, unused -> 100L);
		refresher.start();
		addMouseWheelListener(this);
		addMouseMotionListener(this);
		addMouseListener(this);
		setIgnoreRepaint(true);
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (backgroundImage != null) {
			g.drawImage(
					backgroundImage,
					// Image is 2048x2048, and 0,0 is the top left, but our internal coords treat 0,0 as the center
					translateXscrn(-1024),
					translateYscrn(-1024),
					(int) (2048 * zoomFactor),
					(int) (2048 * zoomFactor),
					getBackground(),
					null);
		}
	}

	@Override
	protected void paintChildren(Graphics g) {
		((Graphics2D) g).setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
		int count = getComponentCount();
		for (int i = 0; i < count; i++) {
			Component comp = getComponent(i);
			if (comp instanceof EntityDoohickey ed) {
				Graphics subG = g.create();
				ed.paintUnder(subG);
				subG.dispose();
			}
		}
		Graphics subG = g.create();
		if (displayTethers.get()) {
			drawTethers(subG);
		}
		subG.dispose();
		super.paintChildren(g);
	}

	private void drawTethers(Graphics g) {
		Graphics2D g2d = (Graphics2D) g;
		// Theoretically improves performance
		g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE);
		// Remove jaggies
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		List<TetherEvent> tethers = mdc.getTethers();
		for (TetherEvent tether : tethers) {
			// Since we need the drawing coordinates of these anyway, just grab them directly
			EntityDoohickey source = things.get(tether.getSource().getId());
			EntityDoohickey target = things.get(tether.getTarget().getId());
			if (source != null && target != null) {
				drawTether(g2d, source, target);
			}
		}
	}

	private void drawTether(Graphics2D g, EntityDoohickey source, EntityDoohickey target) {
		g.setStroke(new BasicStroke(2));
		g.setColor(colorSettings.tetherColor.get());
		g.drawLine(
				translateX(source.x),
				translateY(source.y),
				translateX(target.x),
				translateY(target.y)
		);
	}

	private void setNewBackgroundImage(XivMap map) {
		URL image = map.getImage();
		if (image == null) {
			this.backgroundImage = null;
		}
		else {
			this.backgroundImage = Toolkit.getDefaultToolkit().getImage(image);
		}
	}

	private void resetPanAndZoom() {
		curXpan = 0;
		curYpan = 0;
		if (map != null) {
			zoomFactor = map.getScaleFactor();
		}
		else {
			zoomFactor = 1;
		}
		triggerRefresh();
	}

	private void triggerRefresh() {
		refresher.refreshNow();
	}

	// Force refresh of all combatants by
	private void forceRefreshAllCbts() {
		SwingUtilities.invokeLater(() -> {
			this.things.values().forEach(this::remove);
			this.things.clear();
			this.refresh();
		});

	}

	private volatile List<XivCombatant> combatants = Collections.emptyList();

	public void setCombatants(List<XivCombatant> combatants) {
		this.combatants = new ArrayList<>(combatants);
		requestRefresh();
	}

	private volatile boolean refreshPending;

	private void requestRefresh() {
		if (refreshPending) {
			return;
		}
		refreshPending = true;
		SwingUtilities.invokeLater(this::refresh);
	}

	// For duties such as CoD Chaotic, we don't want to reset map position/zoom on a map change, because the minimap
	// will change during the fight.
	private static boolean areMapsSameArea(XivMap map1, XivMap map2) {
		if (map1 == null && map2 == null) {
			return true;
		}
		if (map1 == null || map2 == null) {
			return false;
		}
		return Objects.equals(map1.getRegion(), map2.getRegion())
		       && Objects.equals(map1.getPlace(), map2.getPlace())
		       && Objects.equals(map1.getSubPlace(), map2.getSubPlace());
	}

	private void refresh() {
		refreshPending = false;
//		log.info("Map refresh");
		List<XivCombatant> combatants = this.combatants;
		XivMap mapNow = mdc.getMap();
		if (!Objects.equals(map, mapNow)) {
			map = mapNow;
			setNewBackgroundImage(mapNow);
			if (!areMapsSameArea(map, mapNow)) {
				resetPanAndZoom();
			}
		}
		if (markers == null) {
			markers = new EnumMap<>(FloorMarker.class);
			for (FloorMarker value : FloorMarker.values()) {
				FloorMarkerDoohickey component = new FloorMarkerDoohickey(value);
				component.setVisible(false);
				markers.put(value, component);
				add(component);
				needZorderCheck = true;
			}
		}
		markers.forEach((marker, component) -> component.reposition(mdc.getFloorMarkers().get(marker)));
		combatants.stream()
				.filter(cbt -> {
					// Further filtering is no longer necessary here since the table pre-filters for us.
					// But we can't exactly display something with no position.
					return cbt.getPos() != null;
				})
				.forEach(cbt -> {
					long id = cbt.getId();
					if (cbt.getPos() == null) {
						return;
					}
					@Nullable CastTracker cast = mdc.getCastFor(cbt);
					// Create if it doesn't already exist
					EntityDoohickey pdh = things.computeIfAbsent(id, (unused) -> createNew(cbt));
					// Update with latest info
					pdh.update(cbt, cast);
				});

		Set<Long> allKeys = things.keySet();
		List<Long> keysToRemove = allKeys.stream().filter(v -> combatants.stream().noneMatch(c -> c.getId() == v)).toList();
		keysToRemove.forEach(k -> {
			EntityDoohickey toRemove = things.remove(k);
			toRemove.setVisible(false);
			remove(toRemove);
		});
		if (needZorderCheck) {
			fixZorder();
		}
		revalidate();
		repaint();
	}

	private EntityDoohickey createNew(XivCombatant cbt) {
		EntityDoohickey player = new EntityDoohickey(cbt);
		add(player);
		needZorderCheck = true;
		return player;
	}

	// Translate in-game X to map coordinates

	/**
	 * @param originalX in-game X coordinate
	 * @return equivalent map coordinates on the current map.
	 */
	private double translateXmap(double originalX) {
		// Already divided by 100
		double c = map.getScaleFactor();
		return (originalX + map.getOffsetX()) * c;
	}

	/**
	 * @param originalY in-game Y coordinate
	 * @return equivalent map coordinates on the current map.
	 */
	private double translateYmap(double originalY) {
		double c = map.getScaleFactor();
		return (originalY + map.getOffsetY()) * c;
	}

	private double translateDistMap(double originalDist) {
		double c = map.getScaleFactor();
		return originalDist * c;
	}

	/**
	 * @param originalX map X coordinate
	 * @return equivalent on-screen coordinate
	 */
	private int translateXscrn(double originalX) {
		return (int) ((originalX * zoomFactor) + curXpan + getWidth() / 2.0);
	}

	/**
	 * @param originalY map Y coordinate
	 * @return equivalent on-screen coordinate
	 */
	private int translateYscrn(double originalY) {
		return (int) ((originalY * zoomFactor) + curYpan + getHeight() / 2.0);
	}

	/**
	 * Translate a raw distance by scaling it appropriately
	 *
	 * @param originalDist map distance
	 * @return equivalent on-screen distance
	 */
	private double translateDistScrn(double originalDist) {
		return originalDist * zoomFactor;
	}

	/**
	 * @param originalX in-game X coordinate
	 * @return equivalent screen coordinate
	 */
	private int translateX(double originalX) {
		return translateXscrn(translateXmap(originalX));
	}

	/**
	 * @param originalY in-game Y coordinate
	 * @return equivalent screen coordinate
	 */
	private int translateY(double originalY) {
		return translateYscrn(translateYmap(originalY));
	}

	private double translateDist(double originalDist) {
		return translateDistScrn(translateDistMap(originalDist));
	}

	private boolean dragActive;

	@Override
	public void mouseDragged(MouseEvent e) {
		if (dragActive) {
			Point curPoint = e.getLocationOnScreen();
			double xDiff = curPoint.x - dragPoint.x;
			double yDiff = curPoint.y - dragPoint.y;
			curXpan += xDiff;
			curYpan += yDiff;
			dragPoint = curPoint;
			triggerRefresh();
		}
	}

	@SuppressWarnings({"NonAtomicOperationOnVolatileField", "NumericCastThatLosesPrecision"})
	@Override
	public void mouseWheelMoved(MouseWheelEvent e) {
		double prevZoomFactor = zoomFactor;
		//Zoom in
		if (e.getWheelRotation() < 0) {
			zoomFactor *= 1.1;
		}
		//Zoom out
		if (e.getWheelRotation() > 0) {
			zoomFactor /= 1.1;
		}
		// Roundoff error - make sure it actually snaps back to exactly 1.0 if it's somewhere close to that.
		if (zoomFactor > 0.94 && zoomFactor < 1.06) {
			zoomFactor = 1.0;
		}
		double xRel = MouseInfo.getPointerInfo().getLocation().getX() - getLocationOnScreen().getX() - getWidth() / 2.0;
		double yRel = MouseInfo.getPointerInfo().getLocation().getY() - getLocationOnScreen().getY() - getHeight() / 2.0;

		double zoomDiv = zoomFactor / prevZoomFactor;

		curXpan = (int) ((zoomDiv) * (curXpan) + (1 - zoomDiv) * xRel);
		curYpan = (int) ((zoomDiv) * (curYpan) + (1 - zoomDiv) * yRel);
		triggerRefresh();

	}

	@Override
	public void mouseMoved(MouseEvent e) {
		// Ignored, we use mouseDragged instead
	}

	@Override
	public void mouseClicked(MouseEvent e) {
		if (e.getButton() == MouseEvent.BUTTON2) {
			resetPanAndZoom();
		}
		else {
			if (e.getComponent() instanceof EntityDoohickey pd) {
				selectionCallback.accept(pd.cbt);
			}
			else {
				selectionCallback.accept(null);
			}
//			log.info("Clicked on {}", getComponentAt(e.getPoint()));
		}
	}

	private static boolean isValidDragBtn(MouseEvent e) {
		int btn = e.getButton();
		return (btn == MouseEvent.BUTTON1 || btn == MouseEvent.BUTTON3 || btn == MouseEvent.BUTTON2);
	}

	@Override
	public void mousePressed(MouseEvent e) {
		if (isValidDragBtn(e)) {
			dragActive = true;
			dragPoint = MouseInfo.getPointerInfo().getLocation();
		}
	}

	@Override
	public void mouseReleased(MouseEvent e) {
		if (isValidDragBtn(e)) {
			dragActive = false;
		}
		triggerRefresh();
	}

	@Override
	public void mouseEntered(MouseEvent e) {
	}

	@Override
	public void mouseExited(MouseEvent e) {
	}

	public void setSelection(@Nullable XivCombatant selection) {
		long newSelection;
		if (selection == null) {
			newSelection = -1;
		}
		else {
			newSelection = selection.getId();
		}
		if (newSelection != this.selection) {
			log.debug("New map selection: {} -> {}",
					this.selection == -1 ? "none" : String.format("0x%X", this.selection),
					newSelection == -1 ? "none" : String.format("0x%X", newSelection));
			this.selection = newSelection;
			fixZorder();
		}
	}

	private void fixZorder() {
		if (SwingUtilities.isEventDispatchThread()) {
			Component[] components = getComponents();
			removeAll();
			Arrays.stream(components).sorted(Comparator.comparing(comp -> {
				if (comp instanceof EntityDoohickey pd) {
					if (pd.isSelected()) {
						return 0;
					}
					else {
						return 1;
					}
				}
				else {
					return 5;
				}
			})).forEach(this::add);
			repaint();
		}
		else {
			SwingUtilities.invokeLater(this::fixZorder);
		}
	}

	public void setSelectionCallback(Consumer<@Nullable XivCombatant> selectionCallback) {
		this.selectionCallback = selectionCallback;
	}

	private static String formatTooltip(XivCombatant cbt) {
		StringBuilder tt = new StringBuilder();
		tt.append(cbt.getName()).append(" (").append(String.format("0x%X, %s)", cbt.getId(), cbt.getId()));
		if (cbt.getbNpcId() != 0) {
			tt.append("\nNPC ID ").append(cbt.getbNpcId());
		}
		if (cbt.getbNpcNameId() != 0) {
			tt.append("\nNPC Name ").append(cbt.getbNpcNameId());
		}
		tt.append('\n').append(cbt.getPos());
		if (cbt.getHp() != null) {
			tt.append("\nHP: ").append(String.format("%s / %s", cbt.getHp().current(), cbt.getHp().max()));
		}
		return tt.toString();
	}


	/**
	 * Component that displays a floor marker
	 */
	private final class FloorMarkerDoohickey extends JPanel {

		private static final int SIZE = 50;
		private double x;
		private double y;

		private FloorMarkerDoohickey(FloorMarker marker) {
			super(null);
			setOpaque(false);
			ScaledImageComponent iconPre = IconTextRenderer.getIconOnly(marker);
			if (iconPre != null) {
				Component icon = iconPre.cloneThis().withNewSize(SIZE);
				add(icon);
				icon.setBounds(0, 0, SIZE, SIZE);
			}
			else {
				log.warn("Could not load marker icon for {}", marker);
			}
		}

		private void reposition(@Nullable Position position) {
			if (position == null) {
				setVisible(false);
			}
			else {
				x = position.x();
				y = position.y();
				setBounds(getBounds());
				setVisible(true);
			}
		}

		@Override
		public int getX() {
			return translateX(this.x) - getSize().width / 2;
		}


		@Override
		public int getY() {
			return translateY(this.y) - getSize().height / 2;
		}

		@Override
		public Rectangle getBounds() {
			return new Rectangle(getX(), getY(), SIZE, SIZE);
		}
	}

	/**
	 * Component that displays a combatant
	 */
	private class EntityDoohickey extends JPanel {

		private static final BasicStroke omenOutline = new BasicStroke(2);
		private static final BasicStroke omenOutlinePre = new BasicStroke(2, omenOutline.getEndCap(), omenOutline.getLineJoin(), omenOutline.getMiterLimit(), new float[]{3.0f, 6.0f}, 0);
		private final JLabel defaultLabel;
		private final XivCombatant cbt;
		// This red should never actually show up
		private Color mainColor = new Color(255, 0, 0);
		private double x;
		private double y;
		private final JPanel inner;
		private Job oldJob;
		private Component icon;
		private final CastBarComponent castBar;
		private final HpBar hpBar;
		private final JLabel nameLabel;
		private final JLabel idLabel;
		private final long cbtId;
		private double facing;
		private @Nullable CastTracker castData;
		private Position pos = Position.of2d(-10_000, -10_000);

		public EntityDoohickey(XivCombatant cbt) {
			this.cbt = cbt;
			cbtId = cbt.getId();
			inner = new JPanel() {
				@Override
				public Color getBackground() {
					return mainColor;
				}
			};
			inner.setBorder(new LineBorder(Color.PINK, 2));
			setLayout(null);
			inner.setLayout(new OverlapLayout());
			inner.setOpaque(false);
			setOpaque(false);
			defaultLabel = new JLabel(cbt.getName());
			formatComponent(cbt);
			RenderUtils.setTooltip(this, formatTooltip(cbt));
			addMouseWheelListener(MapPanel.this);
			addMouseMotionListener(MapPanel.this);
			addMouseListener(MapPanel.this);
			add(inner);
			int outerSize = 100;
			int center = 50;
			setSize(outerSize, outerSize);
			int innerW = inner.getPreferredSize().width;
			int innerH = inner.getPreferredSize().height;
			inner.setBounds(new Rectangle(center - innerW * 2, center - innerH / 2, innerW, innerH));
			this.castBar = new CastBarComponent() {
				@Override
				public void paint(Graphics g) {
					// Make it a little bit transparent
					((Graphics2D) g).setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.90f));
					super.paint(g);
				}
			};

			FacingAngleIndicator arrow = new FacingAngleIndicator();
			arrow.setBounds(center - 10, center - 10, 20, 20);
			add(arrow);

			castBar.setBounds(0, 81, 100, 19);
			add(castBar);
			this.hpBar = new HpBar();
			hpBar.setBounds(0, 62, 100, 19);
			hpBar.setBgTransparency(172);
			hpBar.setFgTransparency(220);
			add(hpBar);

			nameLabel = new JLabel(cbt.getName());
			nameLabel.setBounds(0, 7, 100, 17);
			nameLabel.setOpaque(false);
			nameLabel.setForeground(Color.BLACK);
			nameLabel.setHorizontalAlignment(SwingConstants.CENTER);
			add(nameLabel);

			idLabel = new JLabel(String.format("0x%X", cbt.getId()));
			idLabel.setBounds(0, 23, 100, 17);
			idLabel.setOpaque(false);
			idLabel.setForeground(Color.BLACK);
			idLabel.setHorizontalAlignment(SwingConstants.CENTER);
			add(idLabel);

			revalidate();
		}

		@Override
		public String getToolTipText(MouseEvent event) {
			if (castBar.getBounds().contains(event.getPoint())) {
				return castBar.getToolTipText();
			}
			return super.getToolTipText(event);
		}

		// Setting to -2 so it will never match initially
		long oldHpCurrent = -2;
		long oldHpMax = -2;
		long oldUnresolved = -2;

		// TODO: add debuffs or something to this

		/**
		 * Provide new data to this component and change its appearance accordingly.
		 *
		 * @param cbt      Combatant data
		 * @param castData The current (or recent) cast, if there is one
		 */
		public void update(XivCombatant cbt, @Nullable CastTracker castData) {
			RenderUtils.setTooltip(this, formatTooltip(cbt));
			setBounds(getBounds());
			this.castData = castData;
			Position pos = cbt.getPos();
			if (pos != null) {
				this.x = pos.getX();
				this.y = pos.getY();
				this.facing = pos.getHeading();
				this.pos = pos;
			}
			if (cbt instanceof XivPlayerCharacter pc) {
				Job newJob = pc.getJob();
				if (newJob != oldJob) {
					if (icon != null) {
						inner.remove(icon);
					}
					formatComponent(cbt);
				}
				oldJob = newJob;
				switch (nameDisp.get()) {
					case FULL -> nameLabel.setText(cbt.getName());
					case JOB -> nameLabel.setText(pc.getJob().name());
					case HIDE -> nameLabel.setText("");
				}
			}
			else {
				nameLabel.setText(cbt.getName());
			}
			if (!displayCastBars.get() || castData == null || castData.getEstimatedTimeSinceExpiry().toMillis() > 5000) {
				castBar.setData(null);
			}
			else {
				castBar.setData(castData);
			}

			idLabel.setVisible(displayIds.get());

			if (displayHpBars.get()) {
				HitPoints hp = cbt.getHp();
				long hpCurrent = hp == null ? -1 : hp.current();
				long hpMax = hp == null ? -1 : hp.max();
				long unresolved = mdc.unresolvedDamage(cbt);
				// Ignore updates where nothing changed
				if (hpCurrent == oldHpCurrent && hpMax == oldHpMax && unresolved == oldUnresolved && hpBar.isVisible()) {
					return;
				}
				oldHpCurrent = hpCurrent;
				oldHpMax = hpMax;
				oldUnresolved = unresolved;
				if (hp == null) {
					hpBar.setVisible(false);
				}
				else if (cbt.getType() == CombatantType.FAKE || hp.max() == 1 || hp.current() < 0) {
					hpBar.setVisible(false);
				}
				else {
					hpBar.setVisible(true);
					hpBar.setData(cbt, -1 * unresolved);
				}
				hpBar.revalidate();
			}
			else {
				hpBar.setVisible(false);
			}
		}

		private void formatComponent(XivCombatant cbt) {
			if (cbt instanceof XivPlayerCharacter pc) {
				Job job = pc.getJob();
				if (cbt.isThePlayer()) {
					mainColor = colorSettings.localPcColor.get();
				}
				else if (mdc.getPartyList().contains(cbt)) {
					mainColor = colorSettings.partyMemberColor.get();
				}
				else {
					mainColor = colorSettings.otherPlayerColor.get();
				}
				icon = IconTextRenderer.getComponent(job, defaultLabel, true, false, true, null);
			}
			else {
				icon = null;
				if (cbt.getType() == CombatantType.FAKE) {
					mainColor = colorSettings.fakeEnemyColor.get();
				}
				else if (cbt.getType() == CombatantType.NPC) {
					mainColor = colorSettings.enemyColor.get();
				}
				else {
					mainColor = colorSettings.otherColor.get();
				}
			}
			inner.setBorder(new LineBorder(mainColor));
			inner.setOpaque(true);
			if (icon != null) {
				inner.add(icon);
			}
			validate();
		}

		@Override
		public int getX() {
			return translateX(this.x) - getSize().width / 2;
		}


		@Override
		public int getY() {
			return translateY(this.y) - getSize().height / 2;
		}

		@Override
		public Rectangle getBounds() {
			return new Rectangle(getX(), getY(), getWidth(), getHeight());
		}

		private boolean isSelected() {
			return this.cbtId == selection;
		}

		@Override
		protected void paintComponent(Graphics g) {
			Rectangle bounds = getBounds();
			if (isSelected()) {
				g.setColor(colorSettings.selectedBackground.get());
				g.fillRect(0, 0, bounds.width, bounds.height);
			}
		}

		public void paintUnder(Graphics g) {
			switch (omenDisp.get()) {
				case NONE -> {
					return;
				}
				case ENEMIES_ONLY -> {
					if (cbt.walkParentChain() instanceof XivPlayerCharacter) {
						return;
					}
				}
				case SELECTED_ONLY -> {
					if (!isSelected()) {
						return;
					}
				}
			}
			drawOmens(g);
			if (displayHitbox.get() && cbt.getRadius() > 0 && !cbt.isFake()) {
				drawHitbox((Graphics2D) g);
			}
		}

		private void drawHitbox(Graphics2D g2d) {
			g2d = (Graphics2D) g2d.create();
			try {
				double radius = translateDist(cbt.getRadius());
				double xCenter = translateX(x);
				double yCenter = translateY(y);
				g2d.setColor(cbt.isPc() ? colorSettings.playerHitboxColor.get() : colorSettings.npcHitboxColor.get());
				g2d.setStroke(new BasicStroke(3));
				g2d.drawOval((int) (xCenter - radius), (int) (yCenter - radius), (int) (radius * 2), (int) (radius * 2));
			}
			finally {
				g2d.dispose();
			}
		}

		private void drawOmens(Graphics g) {
			mdc.getOmens(cbtId).forEach(omen -> drawOmen((Graphics2D) g, omen));
		}

		private void drawOmen(Graphics2D g2d, OmenInstance omen) {
			g2d = (Graphics2D) g2d.create();
			try {
				// Cast type 0 and 1 are uninteresting
				// "100" effect range is just a raidwide, don't bother drawing if it's a circle
				ActionOmenInfo oi = omen.info();
				if (oi.isRaidwide()) {
					// Don't need to draw these
					// Maybe add a setting, I can see these having niche use cases
					return;
				}
//				Position castPos;
				Color fillColor;
				Color outlineColor;
				int alpha;
				Duration td = omen.timeDeltaFrom(mdc.getTime());
				// TODO: this seems to conflict with the logic used to determine whether to use the dotted
				// or solid border
				if (td.isNegative()) {
					// casts - start semi transparent
					alpha = 120;
				}
				else {
					// highlight then gradually fade
					// TODO: make the '50' part configurable
					// Fade out over 10s
					double basis = 1 - td.toMillis() / 10000.0;
					if (basis <= 0) {
						return;
					}
					alpha = (int) (200.0 * Math.pow(basis, 3));
				}
				// Don't draw ancient stuff which would be fully transparent anyway.
				// Make selected thing more obvious
				if (MapPanel.this.selection == cbtId) {
					alpha += 50;
				}
				Position omenPos = omen.omenPosition(omenCbt -> MapPanel.this.combatants
						.stream()
						.filter(cbt -> cbt.getId() == omenCbt.getId()).findFirst().orElse(cbt).getPos());
				if (omenPos == null) {
					return;
				}

				if (cbt.walkParentChain() instanceof XivPlayerCharacter) {
					outlineColor = RenderUtils.withAlpha(colorSettings.playerOmenOutlineColor.get(), alpha);
				}
				else if (cbt.getType() == CombatantType.NPC) {
					outlineColor = RenderUtils.withAlpha(colorSettings.npcOmenOutlineColor.get(), alpha);
				}
				else {
					outlineColor = RenderUtils.withAlpha(colorSettings.fakeNpcOmenOutlineColor.get(), alpha);
				}
				fillColor = RenderUtils.withAlpha(outlineColor, alpha / 2);
//				log.info("Outline: {}, Fill: {}", outlineColor.getAlpha(), fillColor.getAlpha());
				double xCenter = translateX(omenPos.x());
				double yCenter = translateY(omenPos.y());
				double radius = translateDist(omen.radius());
				double xModif = translateDist(oi.xAxisModifier());
				/*
				From Valarnin:
				2 - Circle AoE, range directly based on `EffectRange` column
				3 - Cone, range is `EffectRange` + actor's hitbox radius, angle depends on Omen
				4 - Rectangle, range is `EffectRange` + actor's hitbox radius, offset is half of `XAxisModifier` column?
				5 - Circle AoE, range is `EffectRange` + actor's hitbox radius
				6 - I think these are circle AoEs with no actual ground-target AoE shown even as they're resolving, e.g. `Twister`. Should use one of the two formulas (including hitbox raidus or excluding), but not sure.
				8 - "wild charge" rectangle, not sure exactly how width is determined, probably also half of `XAxisModifier`?
				10 - Donut AoE, not sure how inner/outer range is calculated
				11 - cross-shaped AoEs? not 100% sure on this one
				12 - Rectangle, range is `EffectRange`, offset is half of `XAxisModifier` column
				13 - Cone, range is `EffectRange`, angle depends on Omen
				 */
				/*
				My further notes:
				#10 - effect range is the outer radius

				#11 - yes, it's cross

				#12 is a rectangle, but sometimes it is centered on the caster, extending <effectRange> forward and back
					Perhaps cast angle/position will help

				#13 seems to be not only cones, but also things like Omega's "Swivel Cannon" in TOP P5,
				which is a half-room cleave but with the angle offset a bit.

				 */
				// TODO: some of these are wrong due to lack of hitbox size info
				AffineTransform transform = g2d.getTransform();
				boolean isCast = omen.type().isInProgress();
				Stroke outlineStroke = isCast ? omenOutlinePre : omenOutline;
				switch (oi.type().shape()) {
					case CIRCLE -> {
//						g2d.setStroke(omenOutline);
						g2d.setColor(fillColor);
						g2d.fillOval((int) (xCenter - radius), (int) (yCenter - radius), (int) (radius * 2.0), (int) (radius * 2.0));
						g2d.setStroke(outlineStroke);
						g2d.setColor(outlineColor);
						g2d.drawOval((int) (xCenter - radius), (int) (yCenter - radius), (int) (radius * 2.0), (int) (radius * 2.0));
					}
					case DONUT -> {
//						g2d.setStroke(omenOutline);
//						g2d.setColor(fillColor);
//						g2d.fillOval((int) (xCenter - radius), (int) (yCenter - radius), (int) (radius * 2.0), (int) (radius * 2.0));
						g2d.setStroke(outlineStroke);
						g2d.setColor(outlineColor);
						g2d.drawOval((int) (xCenter - radius), (int) (yCenter - radius), (int) (radius * 2.0), (int) (radius * 2.0));
					}
					case RECTANGLE -> {
//						g2d.setStroke(omenOutline);
						transform.translate(xCenter, yCenter);
						transform.rotate(-omenPos.getHeading());
						g2d.setTransform(transform);
						g2d.setColor(fillColor);
						g2d.fillRect((int) -(xModif / 2.0), 0, (int) xModif, (int) radius);
						g2d.setStroke(outlineStroke);
						g2d.setColor(outlineColor);
						g2d.drawRect((int) -(xModif / 2.0), 0, (int) xModif, (int) radius);
					}
					case RECTANGLE_CENTERED -> {
//						g2d.setStroke(omenOutline);
						transform.translate(xCenter, yCenter);
						transform.rotate(-omenPos.getHeading());
						g2d.setTransform(transform);
						g2d.setColor(fillColor);
						g2d.fillRect((int) -(xModif / 2.0), (int) -radius, (int) xModif, (int) (2 * radius));
						g2d.setStroke(outlineStroke);
						g2d.setColor(outlineColor);
						g2d.drawRect((int) -(xModif / 2.0), (int) -radius, (int) xModif, (int) (2 * radius));
					}
					case CROSS -> {
//						g2d.setStroke(omenOutline);
						transform.translate(xCenter, yCenter);
						transform.rotate(-omenPos.getHeading());
						g2d.setTransform(transform);
						g2d.setColor(fillColor);
						g2d.fillRect((int) -(xModif / 2.0), (int) -radius, (int) xModif, (int) (2 * radius));
						g2d.fillRect((int) -radius, (int) -(xModif / 2.0), (int) (2 * radius), (int) xModif);
						g2d.setStroke(outlineStroke);
						g2d.setColor(outlineColor);
						g2d.drawRect((int) -(xModif / 2.0), (int) -radius, (int) xModif, (int) (2 * radius));
						g2d.drawRect((int) -radius, (int) -(xModif / 2.0), (int) (2 * radius), (int) xModif);
					}
					case CONE -> {
//						g2d.setStroke(omenOutline);
						transform.translate(xCenter, yCenter);
						transform.rotate(-omenPos.getHeading() + Math.PI);
						g2d.setTransform(transform);
						g2d.setColor(fillColor);
						int angleDegrees = oi.coneAngle();
						// Arc2D uses the east side as "zero" and counts CCW
						Arc2D.Double arc = new Arc2D.Double(-radius, -radius, 2 * radius, 2 * radius, 90 - angleDegrees / 2.0f, angleDegrees, Arc2D.PIE);
						g2d.setColor(fillColor);
						g2d.fill(arc);
						g2d.setStroke(outlineStroke);
						g2d.setColor(outlineColor);
						g2d.draw(arc);
					}
				}
			}
			finally {
				g2d.dispose();
			}
		}


		@Override
		public Border getBorder() {
			if (isSelected()) {
				return colorSettings.getSelectionBorder();
			}
			else {
				return null;
			}
		}

		private class FacingAngleIndicator extends JComponent {
			@Override
			public void paintComponent(Graphics graph) {
				Graphics2D g = (Graphics2D) graph;
				AffineTransform origTrans = g.getTransform();
				AffineTransform newTrans = new AffineTransform(origTrans);
				Rectangle bounds = getBounds();
				newTrans.translate(bounds.width / 2.0, bounds.height / 2.0);
				newTrans.rotate(-1.0 * facing);
				g.setTransform(newTrans);
				g.setColor(mainColor);
//				g.setColor(new Color(255, 0, 0));
				int sizeBasis = Math.min(bounds.width, bounds.height);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				Polygon poly = new Polygon(
						new int[]{0, (int) (sizeBasis / -3.6), 0, (int) (sizeBasis / 3.6)},
						new int[]{(int) (sizeBasis / 2.2), (int) (sizeBasis / -2.8), (int) (sizeBasis / -5.0), (int) (sizeBasis / -2.8)},
						4);
				g.fillPolygon(poly);
				g.setColor(mainColor.darker().darker());
				g.drawPolygon(poly);
//				g.setColor(new Color(0, 255, 0));
//				g.fillRect(sizeBasis / -8, sizeBasis / -2, sizeBasis / 8, sizeBasis / 2);
				g.setTransform(origTrans);
			}

			@Override
			public Border getBorder() {
				return null;
//				return new LineBorder(Color.BLACK, 1);
			}
		}
	}


}
