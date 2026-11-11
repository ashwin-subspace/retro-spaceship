"""Bullet pattern maths. Pure functions -- no pygame, no global state.

Every function returns velocity vectors, so patterns can be unit-tested without
a display. Patterns are deliberately "chunked" into readable lines and arcs:
research on the genre is clear that scattered single bullets read as unfair,
while grouped shapes read as a puzzle.
"""

import math


def aimed(from_x, from_y, to_x, to_y, speed):
    """One bullet pointed at a target."""
    dx, dy = to_x - from_x, to_y - from_y
    distance = math.hypot(dx, dy) or 1.0
    return (dx / distance * speed, dy / distance * speed)


def angle_to(from_x, from_y, to_x, to_y):
    return math.atan2(to_y - from_y, to_x - from_x)


def from_angle(angle, speed):
    return (math.cos(angle) * speed, math.sin(angle) * speed)


def fan(center_angle, count, arc, speed):
    """``count`` bullets spread evenly across ``arc`` radians, centred."""
    if count <= 1:
        return [from_angle(center_angle, speed)]
    step = arc / (count - 1)
    start = center_angle - arc * 0.5
    return [from_angle(start + step * i, speed) for i in range(count)]


def ring(count, speed, offset=0.0):
    """A full circle of bullets."""
    if count <= 0:
        return []
    step = math.tau / count
    return [from_angle(offset + step * i, speed) for i in range(count)]


def spiral(index, count_per_turn, speed, spin=1.0, offset=0.0):
    """One bullet of a continuing spiral -- call once per shot, incrementing index."""
    step = math.tau / max(1, count_per_turn)
    return from_angle(offset + step * index * spin, speed)


def wall(width, count, gap_index, speed, gap_width=1):
    """A horizontal wall of downward bullets with a gap to fly through.

    Returns ``(x, vx, vy)`` triples. The gap is the whole point: it turns a
    barrage into a route.
    """
    if count <= 0:
        return []
    spacing = width / count
    shots = []
    for i in range(count):
        if gap_index <= i < gap_index + gap_width:
            continue
        x = spacing * (i + 0.5)
        shots.append((x, 0.0, speed))
    return shots


def burst_at(from_x, from_y, to_x, to_y, count, arc, speed):
    """A fan centred on the player -- the workhorse aimed pattern."""
    center = angle_to(from_x, from_y, to_x, to_y)
    return fan(center, count, arc, speed)


def spread_offsets(level):
    """Player weapon geometry: angle offsets (radians) per weapon level."""
    table = {
        1: [0.0],
        2: [-0.08, 0.08],
        3: [-0.16, 0.0, 0.16],
        4: [-0.24, -0.08, 0.08, 0.24],
        5: [-0.32, -0.16, 0.0, 0.16, 0.32],
    }
    return table.get(max(1, min(5, level)), table[1])


def homing_turn(vx, vy, target_dx, target_dy, speed, turn_rate):
    """Rotate a velocity toward a target by at most ``turn_rate`` radians."""
    current = math.atan2(vy, vx)
    desired = math.atan2(target_dy, target_dx)
    delta = (desired - current + math.pi) % math.tau - math.pi
    delta = max(-turn_rate, min(turn_rate, delta))
    return from_angle(current + delta, speed)
