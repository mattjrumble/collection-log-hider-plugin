package com.collectionloghider;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
	name = "Collection Log Hider"
)
public class CollectionLogHiderPlugin extends Plugin
{
	@Inject
	@Getter
	public CollectionLogHiderConfig config;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	private static final Pattern OBTAINED_PATTERN = Pattern.compile("Obtained: (<col=[^>]+>)(\\d+)/(\\d+)(</col>)");

	// Text color the game applies to fully-obtained section titles (green).
	private static final int COMPLETED_TEXT_COLOR = 0x0DC10D;

	// Opacity values the game applies to alternate log section titles.
	private static final int OPACITY_1 = 235;
	private static final int OPACITY_2 = 255;
	// Absolute opacity the game applies to the currently open section title.
	private static final int SELECTED_OPACITY = 200;
	// Amount by which opacity decreases on hover (applied to both normal and selected rows).
	private static final int HOVER_DELTA = 40;

	// Collection interface group ID, used for WidgetLoaded.
	private static final int COLLECTION_GROUP_ID = InterfaceID.Collection.FRAME >> 16;

	// Text layer: holds section name text and the completion color used for filtering.
	private static final int[] TITLE_WIDGETS = {
		InterfaceID.Collection.BOSS_TEXT,
		InterfaceID.Collection.RAID_TEXT,
		InterfaceID.Collection.CLUE_TEXT,
		InterfaceID.Collection.MINIGAME_TEXT,
		InterfaceID.Collection.OTHER_TEXT,
	};

	// Click layer: the game's hit-testing uses these children's positions to determine
	// which section was clicked (param0 in MenuOptionClicked). Must be moved in sync
	// with the text children so that visual position matches click position.
	private static final int[] BACKGROUND_WIDGETS = {
		InterfaceID.Collection.BOSS_BACKGROUND,
		InterfaceID.Collection.RAID_BACKGROUND,
		InterfaceID.Collection.CLUE_BACKGROUND,
		InterfaceID.Collection.MINIGAME_BACKGROUND,
		InterfaceID.Collection.OTHER_BACKGROUND,
	};

	// Scroll containers for each tab, used to shrink the scrollable area after filtering.
	private static final int[] CONTAINER_WIDGETS = {
		InterfaceID.Collection.BOSS_CONTAINER,
		InterfaceID.Collection.RAID_CONTAINER,
		InterfaceID.Collection.CLUE_CONTAINER,
		InterfaceID.Collection.MINIGAME_CONTAINER,
		InterfaceID.Collection.OTHER_CONTAINER,
	};

	// Scrollbar widgets paired 1:1 with CONTAINER_WIDGETS.
	private static final int[] SCROLLBAR_WIDGETS = {
		InterfaceID.Collection.BOSS_SCROLLBAR,
		InterfaceID.Collection.RAID_SCROLLBAR,
		InterfaceID.Collection.CLUE_SCROLLBAR,
		InterfaceID.Collection.MINIGAME_SCROLLBAR,
		InterfaceID.Collection.OTHER_SCROLLBAR,
	};

	// Section-list scroll Y saved just before COLLECTION_DRAW_LIST runs, so we can
	// restore it afterwards. -1 means no save is pending.
	private int savedSectionScrollY = -1;

	// Per-tab startY and strideY saved during filterSectionTitles(), used by
	// restoreSectionTitles() in shutDown() to put section titles back where they were.
	private final int[] sectionListStartY  = new int[TITLE_WIDGETS.length];
	private final int[] sectionListStrideY = new int[TITLE_WIDGETS.length];

	@Provides
	CollectionLogHiderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CollectionLogHiderConfig.class);
	}

	@Override
	protected void startUp()
	{
		clientThread.invoke(() ->
		{
			Widget frame = client.getWidget(InterfaceID.Collection.FRAME);
			if (frame == null || frame.isHidden())
			{
				return;
			}
			if (config.hideCompletedSections())
			{
				filterSectionTitles();
			}
			// Re-trigger the displayed section so our handler applies the plugin's
			// changes. Falls back to first visible section if the current section
			// cannot be identified (e.g. search tab is open).
			if (!retriggerCurrentSection())
			{
				navigateToFirstVisible();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			Widget frame = client.getWidget(InterfaceID.Collection.FRAME);
			if (frame == null || frame.isHidden())
			{
				return;
			}
			// Restore the section-title sidebar before touching the items panel.
			restoreSectionTitles();
			// Re-trigger the current section. Subscriptions are already unregistered at
			// shutDown() time, so COLLECTION_DRAW_LIST fires without our modifications.
			if (!retriggerCurrentSection())
			{
				for (int i = 0; i < BACKGROUND_WIDGETS.length; i++)
				{
					Widget bgWidget = client.getWidget(BACKGROUND_WIDGETS[i]);
					if (bgWidget == null || bgWidget.isHidden())
					{
						continue;
					}
					Widget[] bgChildren = bgWidget.getDynamicChildren();
					if (bgChildren == null || bgChildren.length == 0)
					{
						continue;
					}
					client.menuAction(0, bgWidget.getId(), MenuAction.CC_OP, 1, -1, "Check", "");
					break;
				}
			}
		});
	}

	// Capture the section-list scroll position before the game script can change it.
	@Subscribe
	public void onScriptPreFired(ScriptPreFired scriptPreFired)
	{
		if (scriptPreFired.getScriptId() != ScriptID.COLLECTION_DRAW_LIST || !config.hideCompletedSections())
		{
			return;
		}
		for (int i = 0; i < CONTAINER_WIDGETS.length; i++)
		{
			Widget textWidget = client.getWidget(TITLE_WIDGETS[i]);
			Widget containerWidget = client.getWidget(CONTAINER_WIDGETS[i]);
			if (textWidget == null || containerWidget == null || textWidget.isHidden())
			{
				continue;
			}
			savedSectionScrollY = containerWidget.getScrollY();
			break;
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired scriptPostFired)
	{
		if (scriptPostFired.getScriptId() != ScriptID.COLLECTION_DRAW_LIST)
		{
			return;
		}

		// Update header text immediately to avoid a flash of "Obtained: X/Y".
		if (config.showRemainingCount())
		{
			Widget pageHead = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
			if (pageHead != null)
			{
				updateObtainedText(pageHead);
			}
		}

		// Hide everything immediately so nothing incorrect is visible before the
		// deferred layout runs.
		if (config.hideObtainedItems())
		{
			Widget itemsContainer = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
			if (itemsContainer != null)
			{
				for (Widget item : itemsContainer.getDynamicChildren())
				{
					item.setHidden(true);
				}
			}
		}

		if (config.hideCompletedSections())
		{
			filterSectionTitles();
			// If every item in the section is obtained (opacity == 0), the game
			// auto-opened a completed section on tab switch or tab re-click.
			// Navigate to the first visible section instead.
			if (isCurrentSectionCompleted())
			{
				// Hide the entire right-hand panel (header, section title, obtained text,
				// items grid) so nothing from the wrong section is visible during the one
				// tick it takes for navigateToFirstVisible() to load the correct section.
				// layoutCollectionLog() un-hides MAIN when the correct section fires.
				Widget main = client.getWidget(InterfaceID.Collection.MAIN);
				if (main != null)
				{
					main.setHidden(true);
				}
				navigateToFirstVisible();
				return;
			}
		}

		layoutCollectionLog();
	}

	// Fires when the collection log interface is first opened.
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widgetLoaded)
	{
		if (widgetLoaded.getGroupId() != COLLECTION_GROUP_ID || !config.hideCompletedSections())
		{
			return;
		}
		clientThread.invokeLater(this::filterSectionTitles);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals(CollectionLogHiderConfig.GROUP))
		{
			return;
		}
		clientThread.invoke(() ->
		{
			Widget frame = client.getWidget(InterfaceID.Collection.FRAME);
			if (frame == null || frame.isHidden())
			{
				return;
			}
			if (!retriggerCurrentSection())
			{
				navigateToFirstVisible();
			}
		});
	}

	// Fires when the active tab changes (COLLECTION_LAST_TAB varbit).
	@Subscribe
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{
		if (varbitChanged.getVarbitId() != VarbitID.COLLECTION_LAST_TAB || !config.hideCompletedSections())
		{
			return;
		}
		clientThread.invokeLater(this::filterSectionTitles);
	}

	// Applies all item-panel transformations for the currently displayed section:
	// hides obtained items and/or collapses them into a compact grid, swaps item
	// opacities when switchItemOpacity is on, rewrites the "Obtained: X/Y" header
	// to "Remaining: (Y-X)/Y", and trims the scroll height to the new content size.
	private void layoutCollectionLog()
	{
		Widget pageHead = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		if (pageHead == null)
		{
			return;
		}

		if (config.showRemainingCount())
		{
			updateObtainedText(pageHead);
		}

		// Undo the hide applied in onScriptPostFired when a completed section was
		// detected. Safe to call unconditionally — a no-op if MAIN is already visible.
		Widget main = client.getWidget(InterfaceID.Collection.MAIN);
		if (main != null)
		{
			main.setHidden(false);
		}

		Widget itemsContainer = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (itemsContainer == null)
		{
			return;
		}

		Widget[] items = itemsContainer.getDynamicChildren();
		if (items.length == 0)
		{
			return;
		}

		// Read originalX/Y (game-set, unaffected by our setForcedPosition calls) so
		// that stride computation is correct even if this layout runs more than once.
		int itemWidth = items[0].getWidth();
		int itemHeight = items[0].getHeight();
		int startX = items[0].getOriginalX();
		int startY = items[0].getOriginalY();

		int strideX = itemWidth;
		int strideY = itemHeight;
		if (items.length > 1)
		{
			if (items[1].getOriginalY() == startY)
			{
				strideX = items[1].getOriginalX() - startX;
			}
			else
			{
				strideY = items[1].getOriginalY() - startY;
			}
		}

		int columns = itemsContainer.getWidth() / (strideX > 0 ? strideX : 1);

		// Derive strideY from the first item on the second row (includes vertical padding).
		if (items.length > columns && columns > 0)
		{
			int secondRowY = items[columns].getOriginalY();
			if (secondRowY != startY)
			{
				strideY = secondRowY - startY;
			}
		}

		int slot = 0;
		for (Widget item : items)
		{
			boolean isObtained = (item.getOpacity() == 0);
			if (isObtained)
			{
				if (config.hideObtainedItems())
				{
					item.setHidden(true);
				}
				if (config.switchItemOpacity())
				{
					item.setOpacity(175);
				}
			}
			else
			{
				if (config.hideObtainedItems())
				{
					item.setForcedPosition(
						startX + (slot % columns) * strideX,
						startY + (slot / columns) * strideY
					);
					item.setHidden(false);
					slot++;
				}
				if (config.switchItemOpacity())
				{
					item.setOpacity(0);
				}
			}
		}

		if (config.hideObtainedItems() && columns > 0)
		{
			int numRows = (slot + columns - 1) / columns;
			int newScrollHeight = numRows > 0
				? startY + (numRows - 1) * strideY + itemHeight
				: 0;
			// Prevent tiny scroll-wiggle: round down if overflow is less than one row.
			if (numRows > 0 && newScrollHeight < itemsContainer.getHeight() + strideY)
			{
				newScrollHeight = itemsContainer.getHeight();
			}
			itemsContainer.setScrollHeight(newScrollHeight);
			// invokeAtTickEnd runs after all scripts complete but before the frame renders,
			// so UPDATE_SCROLLBAR sees our corrected height with no visible flash.
			clientThread.invokeAtTickEnd(() -> client.runScript(ScriptID.UPDATE_SCROLLBAR,
				InterfaceID.Collection.ITEMS_SCROLLBAR,
				InterfaceID.Collection.ITEMS_CONTENTS,
				0));
		}
	}

	// Rewrites the "Obtained: X/Y" child widget inside the section header to
	// "Remaining: (Y-X)/Y".
	private void updateObtainedText(Widget headerText)
	{
		Widget[] children = headerText.getChildren();
		if (children == null)
		{
			return;
		}

		for (Widget child : children)
		{
			String text = child.getText();
			if (text == null)
			{
				continue;
			}
			Matcher m = OBTAINED_PATTERN.matcher(text);
			if (m.find())
			{
				int obtained = Integer.parseInt(m.group(2));
				int total = Integer.parseInt(m.group(3));
				int remaining = total - obtained;
				// "Remaining: " is wider than "Obtained: " — expand the widget so the
				// number doesn't wrap to a new line.
				child.setOriginalWidth(child.getOriginalWidth() + 20);
				child.revalidate();
				child.setText(m.replaceFirst("Remaining: " + m.group(1) + remaining + "/" + total + m.group(4)));
				break;
			}
		}
	}

	// Hides the section-title rows that are fully completed (green text) and
	// repacks the remaining rows into a contiguous list. Also rewrites each
	// background child's opacity and hover listeners so the alternating-stripe
	// and selected-highlight colours stay correct after rows are removed.
	// Shrinks the container's scroll height to fit the new row count and restores
	// the saved scroll position (captured in onScriptPreFired) within the new range.
	private void filterSectionTitles()
	{
		for (int i = 0; i < TITLE_WIDGETS.length; i++)
		{
			Widget textWidget = client.getWidget(TITLE_WIDGETS[i]);
			Widget bgWidget = client.getWidget(BACKGROUND_WIDGETS[i]);
			Widget containerWidget = client.getWidget(CONTAINER_WIDGETS[i]);
			if (textWidget == null || bgWidget == null || containerWidget == null
				|| textWidget.isHidden() || bgWidget.isHidden())
			{
				continue;
			}

			Widget[] textChildren = textWidget.getDynamicChildren();
			Widget[] bgChildren = bgWidget.getDynamicChildren();
			if (textChildren == null || bgChildren == null
				|| textChildren.length == 0 || bgChildren.length == 0)
			{
				continue;
			}

			// Detect stride from background children — they drive click detection.
			// Skip children already moved to -10000 by a previous call.
			int startY = 0, prevY = Integer.MIN_VALUE;
			int strideY = bgChildren[0].getOriginalHeight();
			for (Widget child : bgChildren)
			{
				int y = child.getOriginalY();
				if (y < 0)
				{
					continue;
				}
				if (prevY == Integer.MIN_VALUE)
				{
					startY = y;
					prevY = y;
				}
				else
				{
					strideY = y - prevY;
					break;
				}
			}

			sectionListStartY[i]  = startY;
			sectionListStrideY[i] = strideY;

			int count = Math.min(textChildren.length, bgChildren.length);

			String selectedSectionName = getCurrentSectionName();
			int slot = 0;
			for (int j = 0; j < count; j++)
			{
				Widget textChild = textChildren[j];
				Widget bgChild = bgChildren[j];

				if (textChild.getTextColor() == COMPLETED_TEXT_COLOR)
				{
					textChild.setHidden(true);
					textChild.setOriginalY(-10000);
					textChild.revalidate();
					bgChild.setHidden(true);
					bgChild.setOriginalY(-10000);
					bgChild.revalidate();
				}
				else
				{
					int newY = startY + slot * strideY;
					textChild.setHidden(false);
					textChild.setOriginalY(newY);
					textChild.revalidate();
					int rowOpacity = slot % 2 == 0 ? OPACITY_1 : OPACITY_2;
					int hoverOpacity = Math.max(0, rowOpacity - HOVER_DELTA);
					boolean isSelected = selectedSectionName != null
						&& selectedSectionName.equals(textChild.getText());
					// The selected section rests at SELECTED_OPACITY (an absolute value
					// shared by both row colours) and returns to it after hover.
					final int restingOpacity = isSelected ? SELECTED_OPACITY : rowOpacity;
					bgChild.setHidden(false);
					bgChild.setOriginalY(newY);
					bgChild.setOpacity(restingOpacity);
					// Replace the game's index-based hover listeners with ones that use the
					// slot-based opacity as the base, so the highlight is correct for moved rows.
					// The selected section is pinned at SELECTED_OPACITY regardless of hover.
					if (isSelected)
					{
						bgChild.setOnMouseOverListener((JavaScriptCallback) ev -> ev.getSource().setOpacity(SELECTED_OPACITY));
						bgChild.setOnMouseLeaveListener((JavaScriptCallback) ev -> ev.getSource().setOpacity(SELECTED_OPACITY));
					}
					else
					{
						bgChild.setOnMouseOverListener((JavaScriptCallback) ev -> ev.getSource().setOpacity(hoverOpacity));
						bgChild.setOnMouseLeaveListener((JavaScriptCallback) ev -> ev.getSource().setOpacity(restingOpacity));
					}
					bgChild.revalidate();
					slot++;
				}
			}

			int newScrollHeight = slot > 0
				? startY + (slot - 1) * strideY + bgChildren[0].getOriginalHeight()
				: 0;
			// Prevent tiny scroll-wiggle: if the overflow is less than one full row
			// (e.g. due to a top margin), round down to the container height so the
			// scrollbar thumb fills the track with no drag room.
			if (slot > 0 && newScrollHeight < containerWidget.getHeight() + strideY)
			{
				newScrollHeight = containerWidget.getHeight();
			}
			containerWidget.setScrollHeight(newScrollHeight);

			// Clamp the desired scroll position to the new valid range.
			int maxScrollY = Math.max(0, newScrollHeight - containerWidget.getHeight());
			int scrollY = savedSectionScrollY >= 0
				? Math.min(savedSectionScrollY, maxScrollY)
				: Math.min(containerWidget.getScrollY(), maxScrollY);
			savedSectionScrollY = -1;
			containerWidget.setScrollY(scrollY);

			// invokeAtTickEnd runs after all scripts complete but before the frame renders,
			// so UPDATE_SCROLLBAR sees our corrected height with no visible flash.
			final int scrollbarId = SCROLLBAR_WIDGETS[i];
			final int containerId = CONTAINER_WIDGETS[i];
			clientThread.invokeAtTickEnd(() ->
				client.runScript(ScriptID.UPDATE_SCROLLBAR, scrollbarId, containerId, scrollY));
			break;
		}
	}

	// Returns the name of the currently displayed section from the header widget,
	// or null if no section is shown (e.g. collection log is closed or loading).
	private String getCurrentSectionName()
	{
		Widget pageHead = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		if (pageHead == null)
		{
			return null;
		}
		Widget[] headChildren = pageHead.getChildren();
		if (headChildren == null)
		{
			return null;
		}
		for (Widget child : headChildren)
		{
			String text = child.getText();
			if (text == null || text.isEmpty()
				|| text.startsWith("Obtained: ")
				|| text.startsWith("Remaining: ")
				|| OBTAINED_PATTERN.matcher(text).find())
			{
				continue;
			}
			return text;
		}
		return null;
	}

	// Reads the section name from the header widget, finds its background child in
	// the active tab, and simulates a click on it. Returns false if the current
	// section cannot be identified (e.g. no section is shown yet).
	private boolean retriggerCurrentSection()
	{
		String sectionName = getCurrentSectionName();
		if (sectionName == null)
		{
			return false;
		}
		for (int i = 0; i < TITLE_WIDGETS.length; i++)
		{
			Widget textWidget = client.getWidget(TITLE_WIDGETS[i]);
			if (textWidget == null || textWidget.isHidden())
			{
				continue;
			}
			Widget[] textChildren = textWidget.getDynamicChildren();
			if (textChildren == null)
			{
				break;
			}
			for (int j = 0; j < textChildren.length; j++)
			{
				if (sectionName.equals(textChildren[j].getText()))
				{
					client.menuAction(j, BACKGROUND_WIDGETS[i], MenuAction.CC_OP, 1, -1, "Check", "");
					return true;
				}
			}
			break;
		}
		return false;
	}

	// Undoes the changes made by filterSectionTitles(): un-hides every row,
	// restores each row to its original Y position, and restores the container's
	// scroll height. Called on plugin shutdown so the sidebar is left in the same
	// state the game would have produced without the plugin.
	private void restoreSectionTitles()
	{
		for (int i = 0; i < TITLE_WIDGETS.length; i++)
		{
			if (sectionListStrideY[i] == 0)
			{
				continue;
			}
			Widget textWidget = client.getWidget(TITLE_WIDGETS[i]);
			Widget bgWidget = client.getWidget(BACKGROUND_WIDGETS[i]);
			Widget containerWidget = client.getWidget(CONTAINER_WIDGETS[i]);
			if (textWidget == null || bgWidget == null || containerWidget == null)
			{
				continue;
			}
			Widget[] textChildren = textWidget.getDynamicChildren();
			Widget[] bgChildren = bgWidget.getDynamicChildren();
			if (textChildren == null || bgChildren == null)
			{
				continue;
			}
			int count = Math.min(textChildren.length, bgChildren.length);
			int startY = sectionListStartY[i];
			int strideY = sectionListStrideY[i];
			for (int j = 0; j < count; j++)
			{
				int origY = startY + j * strideY;
				textChildren[j].setHidden(false);
				textChildren[j].setOriginalY(origY);
				textChildren[j].revalidate();
				bgChildren[j].setHidden(false);
				bgChildren[j].setOriginalY(origY);
				bgChildren[j].revalidate();
			}
			if (count > 0)
			{
				int fullHeight = startY + (count - 1) * strideY + bgChildren[0].getOriginalHeight();
				containerWidget.setScrollHeight(fullHeight);
				int newScrollY = Math.min(containerWidget.getScrollY(),
					Math.max(0, fullHeight - containerWidget.getHeight()));
				containerWidget.setScrollY(newScrollY);
				final int sbId = SCROLLBAR_WIDGETS[i];
				final int cId = CONTAINER_WIDGETS[i];
				final int sy = newScrollY;
				clientThread.invokeAtTickEnd(() ->
					client.runScript(ScriptID.UPDATE_SCROLLBAR, sbId, cId, sy));
			}
		}
	}

	// Returns true if every item in the currently displayed section is obtained.
	// The game signals obtained items by setting their opacity to 0; any non-zero
	// opacity means at least one item is still missing.
	private boolean isCurrentSectionCompleted()
	{
		Widget itemsContainer = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (itemsContainer == null)
		{
			return false;
		}
		Widget[] items = itemsContainer.getDynamicChildren();
		if (items == null || items.length == 0)
		{
			return false;
		}
		for (Widget item : items)
		{
			// The game sets opacity=0 for obtained items, non-zero for unobtained.
			// If any item is unobtained, the section is incomplete.
			if (item.getOpacity() != 0)
			{
				return false;
			}
		}
		return true;
	}

	// Simulates a click on the first non-hidden background child in the active tab,
	// causing the game to load that section's items. Used when the previously open
	// section is no longer visible (e.g. it was completed and hidden, or the plugin
	// just started and no section has been opened yet).
	private void navigateToFirstVisible()
	{
		for (int i = 0; i < BACKGROUND_WIDGETS.length; i++)
		{
			Widget bgWidget = client.getWidget(BACKGROUND_WIDGETS[i]);
			if (bgWidget == null || bgWidget.isHidden())
			{
				continue;
			}
			Widget[] bgChildren = bgWidget.getDynamicChildren();
			if (bgChildren == null)
			{
				continue;
			}
			for (int j = 0; j < bgChildren.length; j++)
			{
				if (!bgChildren[j].isHidden())
				{
					client.menuAction(j, bgWidget.getId(), MenuAction.CC_OP, 1, -1, "Check", "");
					return;
				}
			}
			return;
		}
	}

}
