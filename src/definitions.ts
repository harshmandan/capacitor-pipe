export interface NPEPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
