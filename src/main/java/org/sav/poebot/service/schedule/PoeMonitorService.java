package org.sav.poebot.service.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.sav.poebot.model.Hour;
import org.sav.poebot.model.QueueData;
import org.sav.poebot.model.ScheduleResponse;
import org.sav.poebot.model.SubQueue;
import org.sav.poebot.service.TelegramNotifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PoeMonitorService {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String URL = "https://www.poe.pl.ua/customs/newgpv-info.php";
	private static final File DATA_DIR = new File("data");

	private final TelegramNotifier telegram;

	/**
	 * Основний цикл моніторингу — кожну хвилину перевіряє оновлення графіка.
	 */
	@Scheduled(fixedRate = 60_000)
	public void checkSchedule() {
		String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
		try {
			ScheduleResponse newData = fetchAndParse(date);
			if (newData == null || newData.schedule().isEmpty()) {
				log.warn("⚠️ Порожній графік для {}", date);
				return;
			}

			if (!DATA_DIR.exists()) DATA_DIR.mkdirs();
			File file = new File(DATA_DIR, "gpv_" + date + ".json");

			ScheduleResponse oldData = file.exists()
					? MAPPER.readValue(Files.readString(file.toPath()), ScheduleResponse.class)
					: null;

			if (!Objects.equals(newData, oldData)) {
				Files.writeString(file.toPath(),
						MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(newData));
				log.info("🔄 Зміни виявлено для {}, файл оновлено", date);
				notifyIfChanged(newData, oldData);
			} else {
				log.info("✅ Без змін ({})", date);
			}

		} catch (Exception e) {
			log.error("❌ Помилка при перевірці графіка: {}", e.getMessage(), e);
		}
	}

	/**
	 * Завантажує HTML і парсить тільки перший блок із графіком.
	 */
	private ScheduleResponse fetchAndParse(String date) throws Exception {
		String payload = "seldate=" + URLEncoder.encode(
				"{\"date_in\":\"" + date + "\"}", StandardCharsets.UTF_8);

		Document doc = Jsoup.connect(URL)
				.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
				.requestBody(payload)
				.method(org.jsoup.Connection.Method.POST)
				.timeout(15_000)
				.post();

		// 🔹 Беремо лише перший блок gpvinfodetail
		Element firstBlock = doc.selectFirst("div.gpvinfodetail");
		if (firstBlock == null) {
			log.warn("⚠️ Не знайдено жодного блоку gpvinfodetail");
			return null;
		}

		Elements rows = firstBlock.select("table.turnoff-scheduleui-table tbody tr");
		if (rows.isEmpty()) {
			log.warn("⚠️ Порожня таблиця графіка");
			return null;
		}

		List<QueueData> schedule = parseSchedule(rows);
		return new ScheduleResponse(date, schedule);
	}

	/**
	 * Парсить HTML-рядки таблиці в структуру графіка.
	 */
	private List<QueueData> parseSchedule(Elements rows) {
		List<QueueData> schedule = new ArrayList<>();
		QueueData currentQueue = null;

		for (Element row : rows) {
			Elements tds = row.select("td");
			if (tds.isEmpty()) continue;

			boolean isMainRow = tds.get(0).hasClass("turnoff-scheduleui-table-queue");

			if (isMainRow) {
				int queueNum = parseIntSafe(tds.get(0).text());
				currentQueue = new QueueData(queueNum, new ArrayList<>());
				int subQueue = parseIntSafe(tds.get(1).text());
				currentQueue.subqueues().add(
						new SubQueue(subQueue, extractLights(tds.subList(2, tds.size())))
				);
			} else if (currentQueue != null) {
				int subQueue = parseIntSafe(tds.get(0).text());
				currentQueue.subqueues().add(
						new SubQueue(subQueue, extractLights(tds.subList(1, tds.size())))
				);
				if (subQueue == 2) {
					schedule.add(currentQueue);
					currentQueue = null;
				}
			}
		}

		return schedule;
	}

	/**
	 * Витягує класи типу light_1/light_2/... із <td>.
	 */
	private List<String> extractLights(List<Element> elements) {
		List<String> list = new ArrayList<>();
		for (Element td : elements) {
			String cls = Optional.ofNullable(td.className())
					.filter(s -> !s.isEmpty())
					.orElse("light_0");
			list.add(cls);
		}
		return list;
	}

	/**
	 * Якщо є зміни — шле повідомлення в Telegram.
	 */
	private void notifyIfChanged(ScheduleResponse newData, ScheduleResponse oldData) {
		for (QueueData q : newData.schedule()) {
			for (SubQueue sq : q.subqueues()) {
				String key = q.queue() + "." + sq.subqueue();

				List<String> newHours = sq.hours();
				List<String> oldHours = findOldHours(oldData, q.queue(), sq.subqueue());
				List<Hour> hours = IntStream.range(0, sq.hours().size())
						.mapToObj(i -> new Hour(newHours.get(i), !newHours.get(i).equals(oldHours.get(i))))
						.toList();

				if (!newHours.equals(oldHours)) {
					telegram.sendMessage(key, buildMessage(q.queue(), sq.subqueue(), hours));
				}
			}
		}
	}

	private List<String> findOldHours(ScheduleResponse oldData, int queue, int subqueue) {
		if (oldData == null) return List.of();
		return oldData.schedule().stream()
				.filter(q -> q.queue() == queue)
				.flatMap(q -> q.subqueues().stream())
				.filter(sq -> sq.subqueue() == subqueue)
				.map(SubQueue::hours)
				.findFirst()
				.orElse(List.of());
	}

	/**
	 * Формує повідомлення в Telegram.
	 */
	private String buildMessage(int queue, int subqueue, List<Hour> hours) {
		StringBuilder sb = new StringBuilder("Черга ")
				.append(queue).append(".").append(subqueue).append("\n");

		String currentStatus = getStatus(hours.getFirst().state());
		int startMin = 0;

		boolean isRangeChanged = false;
		for (int h = 1; h <= hours.size(); h++) {
			String nextStatus = (h < hours.size()) ? getStatus(hours.get(h).state()) : null;
			isRangeChanged = hours.get(h - 1).isChanged() || isRangeChanged;
			if (nextStatus == null || !nextStatus.equals(currentStatus)) {
				int endMin = h * 30;
				sb.append(isRangeChanged ? "<b><u>" : "")
						.append(formatTime(startMin))
						.append(" - ")
						.append(formatTime(endMin))
						.append(isRangeChanged ? "</u></b>" : "")
						.append(" - ")
						.append(currentStatus)
						.append("\n");

				if (nextStatus != null) {
					currentStatus = nextStatus;
					startMin = endMin;
				}
				isRangeChanged = false;
			}
		}
		return sb.toString().trim();
	}

	private String getStatus(String cls) {
		return switch (cls) {
			case "light_2" -> "\uD83D\uDD34";
			case "light_3" -> "\uD83D\uDFE1";
			default -> "\uD83D\uDFE2";
		};
	}

	private String formatTime(int minutes) {
		return String.format("%02d:%02d", minutes / 60, minutes % 60);
	}

	private int parseIntSafe(String text) {
		try {
			return Integer.parseInt(text.replaceAll("\\D", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
