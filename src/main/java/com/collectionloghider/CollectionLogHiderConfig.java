package com.collectionloghider;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(CollectionLogHiderConfig.GROUP)
public interface CollectionLogHiderConfig extends Config
{
	String GROUP = "collectionloghider";

	@ConfigItem(
		keyName = "hideObtainedItems",
		name = "Hide obtained items",
		description = "Hide obtained items from collection log pages"
	)
	default boolean hideObtainedItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "switchItemOpacity",
		name = "Switch item opacity",
		description = "Show obtained items at opacity 1 and unobtained items at opacity 0"
	)
	default boolean switchItemOpacity()
	{
		return true;
	}
}
