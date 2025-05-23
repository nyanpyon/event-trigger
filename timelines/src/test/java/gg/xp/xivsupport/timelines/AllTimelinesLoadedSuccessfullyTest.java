package gg.xp.xivsupport.timelines;

import gg.xp.reevent.events.EventDistributor;
import gg.xp.reevent.events.InitEvent;
import gg.xp.xivdata.data.*;
import gg.xp.xivsupport.lang.GameLanguageInfoEvent;
import gg.xp.xivsupport.sys.XivMain;
import org.picocontainer.MutablePicoContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.util.Set;

public class AllTimelinesLoadedSuccessfullyTest {

	private static final Logger log = LoggerFactory.getLogger(AllTimelinesLoadedSuccessfullyTest.class);

	@DataProvider(parallel = true)
	public static Object[] testCases() {
		return GameLanguage.class.getEnumConstants();
	}

	@Test(dataProvider = "testCases")
	@Parameters
	void didAllTimelinesLoad(GameLanguage value) {
		MutablePicoContainer pico = XivMain.testingMasterInit();
		EventDistributor dist = pico.getComponent(EventDistributor.class);
		dist.acceptEvent(new InitEvent());
		dist.acceptEvent(new GameLanguageInfoEvent(value));
		TimelineManager manager = pico.getComponent(TimelineManager.class);
		Set<Long> timelineZoneIds = TimelineManager.getTimelines().keySet();
		int size = timelineZoneIds.size();
		log.info("Number of timelines: {}", size);
		if (size < 100) {
			Assert.fail("Expected at least 100 timelines, got " + size);
		}
		for (Long timelineZoneId : timelineZoneIds) {
			TimelineProcessor proc = manager.getTimeline(timelineZoneId);
			Assert.assertNotNull(proc, "Timeline Processor was null for zone " + timelineZoneId);
			Assert.assertFalse(proc.getRawEntries().isEmpty(), "Timeline Processor was empty for zone " + timelineZoneId);
		}
		TimelineInfo firstEntry = TimelineCsvReader.readCsv().get(0);
		Assert.assertEquals(firstEntry.zoneId(), 134L);
		Assert.assertEquals(firstEntry.filename(), "test.txt");
	}
}
