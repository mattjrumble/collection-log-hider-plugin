package com.collectionloghider;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;

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

	// Guards against a stale invokeLater callback running after a newer page
	// navigation has already been handled (e.g. rapid double-click).
	private boolean collectionLogDirty = false;

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

		// Hide everything immediately so nothing incorrect is visible before the
		// deferred layout runs.
		if (config.hideObtainedItems()) {
			Widget itemsContainer = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
			if (itemsContainer != null) {
				for (Widget item : itemsContainer.getDynamicChildren()) {
					item.setHidden(true);
				}
			}
		}

		collectionLogDirty = true;
		clientThread.invokeLater(this::layoutCollectionLog);
	}

	private void layoutCollectionLog()
	{
		if (!collectionLogDirty)
		{
			return;
		}
		collectionLogDirty = false;

		Widget pageHead = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
		if (pageHead == null)
		{
			return;
		}

		Widget itemsContainer = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
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
			log.debug("isObtained: {}", isObtained);
			log.debug("getOpacity1: {}", item.getOpacity());
			if (isObtained) {
				if (config.hideObtainedItems()) {
					item.setHidden(true);
				}
				if (config.switchItemOpacity()) {
					log.debug("setOpacity(175)");
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
					log.debug("setOpacity(0)");
					item.setOpacity(0);
				}
			}
			log.debug("getOpacity2: {}", item.getOpacity());
		}
	}

}
