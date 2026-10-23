package com.exam.allocation.service;

import com.exam.allocation.model.Room;
import com.exam.allocation.repository.RoomRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomImportServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Spy
    private FileTableReader fileTableReader = new FileTableReader();

    @InjectMocks
    private RoomImportService roomImportService;

    @Test
    void importsRoomNumberAndDeskCountAndRejectsInvalidRows() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "rooms.csv", "text/csv",
                "Desk Num,Room Number\n12,R-101\n0,R-102\n".getBytes());
        when(roomRepository.findByRoomNumber("R-101")).thenReturn(null);
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomImportService.RoomImportResult result = roomImportService.importRooms(file);

        assertEquals(1, result.getImported());
        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getInvalid());
        assertEquals("R-101", result.getPreview().get(0).get("roomNumber"));
        assertEquals("12", result.getPreview().get(0).get("desks"));
        verify(roomRepository).save(org.mockito.ArgumentMatchers.argThat(room ->
                room.getRowsCount() == 3 && room.getColumnsCount() == 4
                        && room.getStudentsPerDesk() == 3 && room.getCapacity() == 36));
        verify(roomRepository, never()).findByRoomNumber("R-102");
    }
}
