import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

/** The words a failed change is shown with: the problem the API answered, in the interface language. */
export function useFailureText() {
  const problemMessage = useProblemMessage();
  return (error: unknown) => problemMessage(presentProblem(error, "mutation").message);
}
