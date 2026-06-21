package com.collectionloghider;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
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
		if (config.hideObtainedItems()) {
			Widget itemsContainer = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
			if (itemsContainer != null) {
				for (Widget item : itemsContainer.getDynamicChildren()) {
					item.setHidden(true);
				}
			}
		}

		layoutCollectionLog();
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
			if (isObtained) {
				if (config.hideObtainedItems()) {
					item.setHidden(true);
				}
				if (config.switchItemOpacity()) {
					item.setOpacity(175);
				}
			} else {
				if (config.hideObtainedItems()) {
					item.setForcedPosition(
						startX + (slot % columns) * strideX,
						startY + (slot / columns) * strideY
					);
					item.setHidden(false);
					slot++;
				}
				if (config.switchItemOpacity()) {
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

}
