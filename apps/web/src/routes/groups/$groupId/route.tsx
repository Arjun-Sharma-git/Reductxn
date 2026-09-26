import { createFileRoute, Outlet } from '@tanstack/react-router';
import { z } from 'zod';

/**
 * A group's routes, laid out together.
 *
 * The layout exists for what every group screen will need and none of them should own twice: the group's
 * identifier from the path, and (later) the group itself, fetched once and shared by its screens. Today it is
 * the seam where that lands, and a thin one - which is the shape RULE F4 asks of a route: parse, render, no
 * business logic.
 */
export const Route = createFileRoute('/groups/$groupId')({
  validateSearch: z.object({ lang: z.string().optional() }),
  component: GroupLayout,
});

function GroupLayout() {
  return <Outlet />;
}
