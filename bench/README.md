# FFI benchmarks

From repo root run:

```sh
bb bench             # Test the current worktree, including uncommitted changes
bb bench HEAD^ HEAD  # Compare two commits, tags, or branches
```

Uses Criterium with an in-memory database. Prints mean times and percentage
changes. Saves logs and results to `bench/results/<run-id>/`.
