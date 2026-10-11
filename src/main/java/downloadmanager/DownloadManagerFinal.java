package downloadmanager;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

/**
 * Менеджер завантажень: кілька незалежних завантажень, кожне виконується
 * в окремому потоці й не блокує графічний інтерфейс.
 *
 * <p>Правила, яких дотримується програма:
 * <ul>
 *   <li>довга операція (завантаження) працює у фоновому потоці, а не
 *       в обробнику кнопки, інакше EDT зайнятий і вікно «зависає»;</li>
 *   <li>компоненти Swing змінюються тільки з EDT — фонові потоки
 *       передають йому роботу через {@link SwingUtilities#invokeLater};</li>
 *   <li>спільний лічильник мегабайтів змінюється кількома потоками,
 *       тому доступ до нього синхронізований.</li>
 * </ul>
 */
public class DownloadManagerFinal {

    private static final int DOWNLOAD_COUNT = 5;

    /** Назви файлів, що «завантажуються». */
    private static final String[] NAMES = {
        "Фільм.mkv", "Музика.zip", "Документи.pdf", "Гра.iso",
        "Фото.rar"
    };

    /** Розміри файлів у МБ (кратні 10: за крок завантажується 10%). */
    private static final int[] SIZES_MB = {700, 120, 40, 1500, 250};

    /** Затримка одного кроку (10%) — різна, щоб було видно паралельність. */
    private static final int[] STEP_DELAY_MS = {900, 500, 300, 1200, 600};

    private static final int STEP_PERCENT = 10;
    private static final int MAX_PERCENT = 100;

    // ---------- Спільний лічильник «Загалом завантажено» ----------

    /** Спільний ресурс: змінюється кількома потоками одночасно. */
    private static int totalDownloaded = 0;

    /** Замок, що захищає {@link #totalDownloaded}. */
    private static final Object lock = new Object();

    // ---------- Спільний лічильник «Активних завантажень» ----------

    /** Скільки завантажень виконується прямо зараз. */
    private static int activeDownloads = 0;

    /**
     * Окремий замок для {@link #activeDownloads}: це логічно інша
     * сутність, ніж мегабайти, тому потоки, що оновлюють один лічильник,
     * не мусять чекати на інший.
     */
    private static final Object activeLock = new Object();

    // ---------- Стан кожного завантаження ----------

    /**
     * Прапорці скасування. Елементи звичайного масиву не можна оголосити
     * {@code volatile}, тому використовується {@link AtomicBoolean}: запис
     * з EDT гарантовано побачить потік завантаження.
     */
    private static final AtomicBoolean[] cancelled =
            new AtomicBoolean[DOWNLOAD_COUNT];

    /** Потоки завантажень (читаються й записуються тільки на EDT). */
    private static final Thread[] threads = new Thread[DOWNLOAD_COUNT];

    // ---------- Компоненти GUI ----------

    private static final JProgressBar[] progressBars =
            new JProgressBar[DOWNLOAD_COUNT];
    private static final JButton[] startButtons = new JButton[DOWNLOAD_COUNT];
    private static final JButton[] cancelButtons =
            new JButton[DOWNLOAD_COUNT];
    private static final JLabel[] statusLabels = new JLabel[DOWNLOAD_COUNT];
    private static JLabel totalLabel;
    private static JLabel activeLabel;

    public static void main(String[] args) {
        // Вікно створюється на EDT.
        SwingUtilities.invokeLater(DownloadManagerFinal::createAndShowGui);
    }

    private static void createAndShowGui() {
        JFrame frame = new JFrame("Менеджер завантажень");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JPanel downloadsPanel = new JPanel(new GridLayout(DOWNLOAD_COUNT, 1));
        for (int i = 0; i < DOWNLOAD_COUNT; i++) {
            cancelled[i] = new AtomicBoolean(false);
            downloadsPanel.add(createDownloadPanel(i));
        }
        frame.add(downloadsPanel, BorderLayout.CENTER);

        totalLabel = new JLabel(totalText(0));
        activeLabel = new JLabel(activeText(0));
        JPanel bottomPanel = new JPanel(new GridLayout(1, 2));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        bottomPanel.add(totalLabel);
        bottomPanel.add(activeLabel);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * Створює рядок одного завантаження: назва, прогрес-бар, кнопки
     * «Старт» і «Скасувати», статус.
     */
    private static JPanel createDownloadPanel(int index) {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        JLabel nameLabel = new JLabel(
                NAMES[index] + " (" + SIZES_MB[index] + " МБ)");
        nameLabel.setPreferredSize(new Dimension(170, 24));
        panel.add(nameLabel, BorderLayout.WEST);

        JProgressBar progressBar = new JProgressBar(0, MAX_PERCENT);
        progressBar.setStringPainted(true); // показувати відсоток на барі
        progressBar.setPreferredSize(new Dimension(260, 24));
        panel.add(progressBar, BorderLayout.CENTER);

        JButton startButton = new JButton("Старт");
        JButton cancelButton = new JButton("Скасувати");
        cancelButton.setEnabled(false);
        JLabel statusLabel = new JLabel("Очікує");
        statusLabel.setPreferredSize(new Dimension(150, 24));

        startButton.addActionListener(e -> startDownload(index));
        cancelButton.addActionListener(e -> cancelDownload(index));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controls.add(startButton);
        controls.add(cancelButton);
        controls.add(statusLabel);
        panel.add(controls, BorderLayout.EAST);

        progressBars[index] = progressBar;
        startButtons[index] = startButton;
        cancelButtons[index] = cancelButton;
        statusLabels[index] = statusLabel;
        return panel;
    }

    /** Обробник «Старт» (EDT): запускає завантаження в новому потоці. */
    private static void startDownload(int index) {
        cancelled[index].set(false);
        progressBars[index].setValue(0);
        statusLabels[index].setText("Завантаження...");
        startButtons[index].setEnabled(false);
        cancelButtons[index].setEnabled(true);

        Thread thread = new Thread(() -> runDownload(index),
                "download-" + (index + 1));
        thread.setDaemon(true);
        threads[index] = thread;
        thread.start(); // обробник одразу повертається — EDT вільний
    }

    /**
     * Обробник «Скасувати» (EDT): ставить прапорець і перериває потік,
     * щоб він не чекав кінця {@code sleep}. Потік завершується сам,
     * EDT нічого не чекає — застосунок не зависає.
     */
    private static void cancelDownload(int index) {
        cancelled[index].set(true);
        cancelButtons[index].setEnabled(false);
        statusLabels[index].setText("Скасування...");
        Thread thread = threads[index];
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Тіло потоку завантаження (виконується НЕ на EDT). */
    private static void runDownload(int index) {
        changeActive(+1); // завантаження почалося — на самому початку потоку
        int progress = 0;
        boolean completed = false;
        try {
            while (progress < MAX_PERCENT) {
                if (cancelled[index].get()) {
                    return; // виходимо з методу — потік завершується сам
                }
                Thread.sleep(STEP_DELAY_MS[index]); // «завантажуємо» 10%
                progress += STEP_PERCENT;

                int currentProgress = progress;
                // GUI оновлюємо тільки через EDT.
                SwingUtilities.invokeLater(
                        () -> progressBars[index].setValue(currentProgress));
                addToTotal(SIZES_MB[index] * STEP_PERCENT / MAX_PERCENT);
            }
            completed = true;
        } catch (InterruptedException e) {
            // Потік перервано кнопкою «Скасувати» — це і є скасування.
        } finally {
            // finally виконується в ОБОХ випадках: і коли дійшли до 100%,
            // і коли вийшли через return/InterruptedException після
            // скасування. Тому лічильник активних ніколи не «застрягає».
            // Зменшуємо його до того, як знову ввімкнеться «Старт».
            changeActive(-1);
            finishDownload(index, completed, progress);
        }
    }

    /** Повертає кнопки в початковий стан і показує результат. */
    private static void finishDownload(int index, boolean completed,
            int progress) {
        String status = completed
                ? "Завершено"
                : "Скасовано на " + progress + "%";
        SwingUtilities.invokeLater(() -> {
            statusLabels[index].setText(status);
            startButtons[index].setEnabled(true);
            cancelButtons[index].setEnabled(false);
        });
    }

    /**
     * Додає мегабайти до спільного лічильника. Операція {@code +=} не
     * атомарна (прочитати, додати, записати), тому без синхронізації два
     * потоки могли б «загубити» оновлення одне одного (race condition).
     * Мітка оновлюється всередині блоку, щоб значення з'являлися на екрані
     * в тому самому порядку, в якому змінювався лічильник.
     */
    private static void addToTotal(int amount) {
        synchronized (lock) {
            totalDownloaded += amount;
            int current = totalDownloaded;
            SwingUtilities.invokeLater(
                    () -> totalLabel.setText(totalText(current)));
        }
    }

    /**
     * Змінює кількість активних завантажень: +1 на початку потоку,
     * -1 при завершенні чи скасуванні.
     */
    private static void changeActive(int delta) {
        synchronized (activeLock) {
            activeDownloads += delta;
            int current = activeDownloads;
            SwingUtilities.invokeLater(
                    () -> activeLabel.setText(activeText(current)));
        }
    }

    private static String activeText(int active) {
        return "Активних завантажень: " + active + " з " + DOWNLOAD_COUNT;
    }

    private static String totalText(int megabytes) {
        return "Загалом завантажено: " + megabytes + " МБ";
    }
}
