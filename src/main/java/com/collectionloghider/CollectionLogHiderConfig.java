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
		description = "Show obtained items as opaque and unobtained items as translucent"
	)
	default boolean switchItemOpacity()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showRemainingCount",
		name = "Show remaining count",
		description = "Replace 'Obtained: X/Y' with 'Remaining: (Y-X)/Y' in the collection log header"
	)
	default boolean showRemainingCount()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideCompletedSections",
		name = "Hide completed sections",
		description = "Remove fully-obtained sections from the sidebar list"
	)
	default boolean hideCompletedSections()
	{
		return true;
	}
}
