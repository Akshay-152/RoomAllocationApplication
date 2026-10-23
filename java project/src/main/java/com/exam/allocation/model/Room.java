package com.exam.allocation.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "room_type")
public class Room {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String roomNumber;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private int rowsCount;
    
    @Column(nullable = false)
    private int columnsCount;

    /** How many students share one desk/bench (exam desks often seat 2). */
    private int studentsPerDesk = 1;

    /** Optional friendly name, e.g. "Computer Lab" or "Seminar Room" (blank = show the room number). */
    private String name;

    /** Building the room is in, e.g. "Block A". */
    private String building;

    /** Floor number (0 = ground floor). */
    private Integer floor;

    /** Inactive rooms are kept for history but never used for new allocations. */
    @Column(nullable = false)
    private boolean active = true;

    // @JsonIgnore breaks the Seat -> Room -> Seat serialization cycle that
    // previously made /api/rooms return an infinitely nested JSON payload.
    @JsonIgnore
    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL)
    private List<Seat> seats;

    // Default constructor
    public Room() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRoomNumber() { return roomNumber; }
    public void setRoomNumber(String roomNumber) { this.roomNumber = roomNumber; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public int getRowsCount() { return rowsCount; }
    public void setRowsCount(int rowsCount) { this.rowsCount = rowsCount; }

    public int getColumnsCount() { return columnsCount; }
    public void setColumnsCount(int columnsCount) { this.columnsCount = columnsCount; }

    /** Normalised accessor: a room always has at least one student per desk. */
    public int getStudentsPerDesk() { return studentsPerDesk < 1 ? 1 : studentsPerDesk; }
    public void setStudentsPerDesk(int studentsPerDesk) {
        this.studentsPerDesk = studentsPerDesk < 1 ? 1 : studentsPerDesk;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBuilding() { return building; }
    public void setBuilding(String building) { this.building = building; }

    public Integer getFloor() { return floor; }
    public void setFloor(Integer floor) { this.floor = floor; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    /** Human readable name used in charts and seating slips, e.g. "Room 6, Block A, Floor 1". */
    public String getDisplayName() {
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isBlank()) {
            sb.append(name.trim());
            if (roomNumber != null) sb.append(" (").append(roomNumber).append(")");
        } else {
            sb.append(roomNumber == null ? "Room" : roomNumber);
        }
        if (building != null && !building.isBlank()) sb.append(", ").append(building.trim());
        if (floor != null) sb.append(", Floor ").append(floor);
        return sb.toString();
    }

    public List<Seat> getSeats() { return seats; }
    public void setSeats(List<Seat> seats) { this.seats = seats; }
}
