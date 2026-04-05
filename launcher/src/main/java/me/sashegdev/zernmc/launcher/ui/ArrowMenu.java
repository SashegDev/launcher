package me.sashegdev.zernmc.launcher.ui;

import me.sashegdev.zernmc.launcher.utils.ZAnsi;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.util.List;

public class ArrowMenu {

    private final String title;
    private final List<String> options;
    private int selected = 0;
    private final Terminal terminal;

    private static final int VISIBLE_ITEMS = 7;   // сколько строк показывать в списке

    public ArrowMenu(String title, List<String> options) throws IOException {
        this.title = title;
        this.options = options;
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .jna(true)
                .build();
    }

    public int show() throws IOException {
        terminal.enterRawMode();
        terminal.puts(InfoCmp.Capability.clear_screen);
        terminal.puts(InfoCmp.Capability.cursor_invisible);

        try {
            while (true) {
                printPagedMenu();
                int key = terminal.reader().read();

                if (key == 'w' || key == 'W' || key == 'ц' || key == 'Ц') {        // Up
                    selected = (selected - 1 + options.size()) % options.size();
                } 
                else if (key == 's' || key == 'S' || key == 'ы' || key == 'Ы') {   // Down
                    selected = (selected + 1) % options.size();
                } 
                else if (key == 13 || key == 10) {                  // Enter
                    return selected;
                } 
                else if (key == 27) {                               // Esc
                    return -1;
                }
            }
        } finally {
            terminal.puts(InfoCmp.Capability.cursor_visible);
            terminal.close();
        }
    }

    private void printPagedMenu() {
        StringBuilder sb = new StringBuilder();
        sb.append("\033[H\033[2J");

        // Заголовок (фиксированный)
        sb.append(ZAnsi.header("=== ZernMC Launcher ===")).append("\n\n");
        sb.append(ZAnsi.yellow(title)).append("\n\n");

        // Вычисляем диапазон отображаемых элементов
        int start = Math.max(0, selected - (VISIBLE_ITEMS / 2));
        int end = Math.min(options.size(), start + VISIBLE_ITEMS);

        // Если в конце списка — подтягиваем вверх
        if (end - start < VISIBLE_ITEMS && start > 0) {
            start = Math.max(0, end - VISIBLE_ITEMS);
        }

        for (int i = start; i < end; i++) {
            String line = options.get(i);
            if (i == selected) {
                sb.append(ZAnsi.selected(line)).append("\n");
            } else {
                sb.append(ZAnsi.white("    " + line)).append("\n");
            }
        }

        // Подсказка внизу (фиксированная)
        sb.append("\n")
          .append(ZAnsi.white("W/S (Ц/Ы) - перемещение | Enter - выбрать | Esc - назад"));

        System.out.print(sb);
    }
}