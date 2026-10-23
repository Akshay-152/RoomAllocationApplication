package com.exam.allocation.service;

import com.exam.allocation.model.Room;
import com.exam.allocation.repository.RoomRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Imports room numbers and desk counts from PDF, Excel, CSV, TSV or TXT files. */
@Service
public class RoomImportService {

    private static final int DEFAULT_STUDENTS_PER_DESK = 3;
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("\\d+(?:\\.0+)?");

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private FileTableReader fileTableReader;

    public static class RoomImportResult {
        private String fileName;
        private int imported;
        private int updated;
        private int invalid;
        private int skipped;
        private List<Map<String, String>> preview = new ArrayList<>();
        private List<String> notes = new ArrayList<>();

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public int getImported() { return imported; }
        public void setImported(int imported) { this.imported = imported; }
        public int getUpdated() { return updated; }
        public void setUpdated(int updated) { this.updated = updated; }
        public int getInvalid() { return invalid; }
        public void setInvalid(int invalid) { this.invalid = invalid; }
        public int getSkipped() { return skipped; }
        public void setSkipped(int skipped) { this.skipped = skipped; }
        public List<Map<String, String>> getPreview() { return preview; }
        public List<String> getNotes() { return notes; }
    }

    public RoomImportResult importRooms(MultipartFile file) throws IOException {
        List<String[]> rows = fileTableReader.read(file);
        RoomImportResult result = new RoomImportResult();
        result.setFileName(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());

        int headerRow = -1;
        Map<String, Integer> mapping = new HashMap<>();
        for (int i = 0; i < Math.min(rows.size(), 5); i++) {
            Map<String, Integer> candidate = mapHeader(rows.get(i));
            if (candidate.size() == 2) {
                headerRow = i;
                mapping = candidate;
                break;
            }
        }
        if (headerRow < 0) {
            mapping.put("roomNumber", 0);
            mapping.put("desks", 1);
            result.getNotes().add("No header row recognised - assumed column order: Room Number, Desks.");
        } else {
            result.getNotes().add("Header found in row " + (headerRow + 1) + ".");
        }

        for (int i = headerRow >= 0 ? headerRow + 1 : 0; i < rows.size(); i++) {
            String[] cells = rows.get(i);
            if (isBlank(cells)) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }

            String roomNumber = valueAt(cells, mapping.get("roomNumber"));
            String rawDesks = valueAt(cells, mapping.get("desks"));
            Integer desks = parsePositiveInteger(rawDesks);
            if (roomNumber == null || desks == null || desks > Integer.MAX_VALUE / DEFAULT_STUDENTS_PER_DESK) {
                result.setInvalid(result.getInvalid() + 1);
                if (result.getNotes().size() < 20) {
                    result.getNotes().add("Row " + (i + 1) + ": room number and a positive desk count are required.");
                }
                continue;
            }

            int[] grid = predictGrid(desks);
            Room room = roomRepository.findByRoomNumber(roomNumber);
            boolean update = room != null;
            if (!update) room = new Room();
            room.setRoomNumber(roomNumber);
            room.setRowsCount(grid[0]);
            room.setColumnsCount(grid[1]);
            room.setStudentsPerDesk(DEFAULT_STUDENTS_PER_DESK);
            room.setCapacity(desks * DEFAULT_STUDENTS_PER_DESK);
            room = roomRepository.save(room);

            if (update) result.setUpdated(result.getUpdated() + 1);
            else result.setImported(result.getImported() + 1);
            if (result.getPreview().size() < 20) {
                Map<String, String> previewRow = new LinkedHashMap<>();
                previewRow.put("roomNumber", room.getRoomNumber());
                previewRow.put("desks", String.valueOf(desks));
                previewRow.put("status", update ? "UPDATED" : "NEW");
                result.getPreview().add(previewRow);
            }
        }
        return result;
    }

    private Map<String, Integer> mapHeader(String[] cells) {
        Map<String, Integer> mapping = new HashMap<>();
        if (cells == null) return mapping;
        for (int i = 0; i < cells.length; i++) {
            String label = normalize(cells[i]);
            if (isRoomHeader(label)) mapping.put("roomNumber", i);
            if (isDeskHeader(label)) mapping.put("desks", i);
        }
        return mapping;
    }

    private boolean isRoomHeader(String label) {
        return label.equals("room") || label.equals("roomno") || label.equals("roomnum")
                || label.equals("roomnumber") || label.equals("roomid");
    }

    private boolean isDeskHeader(String label) {
        return label.equals("desk") || label.equals("desks") || label.equals("deskno")
                || label.equals("desknum") || label.equals("desknumber") || label.equals("deskcount")
                || label.equals("numberofdesks") || label.equals("noofdesks") || label.equals("noofdesk");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private String valueAt(String[] cells, Integer index) {
        if (cells == null || index == null || index < 0 || index >= cells.length || cells[index] == null) return null;
        String value = cells[index].trim();
        return value.isEmpty() ? null : value;
    }

    private Integer parsePositiveInteger(String value) {
        if (value == null || !POSITIVE_INTEGER.matcher(value.trim()).matches()) return null;
        try {
            int parsed = new BigDecimal(value.trim()).intValueExact();
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }

    private boolean isBlank(String[] cells) {
        if (cells == null) return true;
        for (String cell : cells) if (cell != null && !cell.trim().isEmpty()) return false;
        return true;
    }

    private int[] predictGrid(int desks) {
        for (int rows : new int[]{3, 4, 2}) {
            int columns = desks / rows;
            if (desks % rows == 0 && columns >= rows && columns <= 12) return new int[]{rows, columns};
        }
        int[] best = null;
        for (int rows = 1; rows * rows <= desks; rows++) {
            if (desks % rows == 0) {
                int columns = desks / rows;
                if (best == null || columns - rows < best[1] - best[0]) best = new int[]{rows, columns};
            }
        }
        return best == null ? new int[]{1, desks} : best;
    }
}
