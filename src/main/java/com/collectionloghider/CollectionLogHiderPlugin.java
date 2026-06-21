package com.collectionloghider;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
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
	// Amount by which opacity decreases on hover.
	private static final int HOVER_DELTA = 60;

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

	@Provides
	CollectionLogHiderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CollectionLogHiderConfig.class);
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
	}

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
			if (text.startsWith("Obtained: "))
			{
				child.setText("");
				break;
			}
		}
	}

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

			int count = Math.min(textChildren.length, bgChildren.length);

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
					bgChild.setHidden(false);
					bgChild.setOriginalY(newY);
					bgChild.setOpacity(rowOpacity);
					// Replace the game's index-based hover listeners with ones that use the
					// slot-based opacity as the base, so the highlight is correct for moved rows.
					bgChild.setOnMouseOverListener((JavaScriptCallback) ev -> ev.getSource().setOpacity(hoverOpacity));
					bgChild.setOnMouseLeaveListener((JavaScriptCallback) ev -> ev.getSource().setOpacity(rowOpacity));
					bgChild.revalidate();
					slot++;
				}
			}

			int newScrollHeight = slot > 0
				? startY + (slot - 1) * strideY + bgChildren[0].getOriginalHeight()
				: 0;
			containerWidget.setScrollHeight(newScrollHeight);
			containerWidget.revalidateScroll();
			break;
		}
	}

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
					// Simulate a "Check" click on this child. The background widget is an
					// IF3 list component: param0 = child index, param1 = widget ID.
					client.menuAction(j, bgWidget.getId(), MenuAction.CC_OP, 1, -1, "Check", "");
					return;
				}
			}
			return;
		}
	}

}
