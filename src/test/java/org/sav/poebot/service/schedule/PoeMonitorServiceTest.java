package org.sav.poebot.service.schedule;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.sav.poebot.model.QueueData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PoeMonitorServiceTest {

	@Test
	void parsesNewGpvTableFormat() {
		Document document = Jsoup.parse("""
				<table class="gpv-table">
					<tbody>
						<tr>
							<td class="gpv-q">1.1</td>
							<td class="gpv-c gpv-s1 gpv-h gpv-past"></td>
							<td class="gpv-c gpv-s2"></td>
							<td class="gpv-c gpv-s3"></td>
						</tr>
						<tr>
							<td class="gpv-q">1.2</td>
							<td class="gpv-c gpv-s2"></td>
							<td class="gpv-c gpv-s1"></td>
						</tr>
						<tr>
							<td class="gpv-q">2.1</td>
							<td class="gpv-c gpv-s3"></td>
						</tr>
					</tbody>
				</table>
				""");
		PoeMonitorService service = new PoeMonitorService(null);

		List<QueueData> schedule = service.parseSchedule(
				document.select("table.gpv-table tbody tr"));

		assertEquals(2, schedule.size());
		assertEquals(1, schedule.get(0).queue());
		assertEquals(2, schedule.get(0).subqueues().size());
		assertEquals(List.of("light_1", "light_2", "light_3"),
				schedule.get(0).subqueues().get(0).hours());
		assertEquals(List.of("light_2", "light_1"),
				schedule.get(0).subqueues().get(1).hours());
		assertEquals(2, schedule.get(1).queue());
		assertEquals(List.of("light_3"), schedule.get(1).subqueues().get(0).hours());
	}
}
