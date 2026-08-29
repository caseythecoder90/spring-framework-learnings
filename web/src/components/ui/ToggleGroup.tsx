export interface Option<T> {
  label: string;
  value: T;
}

interface Props<T> {
  label: string;
  options: ReadonlyArray<Option<T>>;
  value: T;
  onChange: (value: T) => void;
  /** Dimmed and inert when this branch cannot be reached. */
  disabled?: boolean;
}

/**
 * The one control this site uses for picking between a handful of states.
 * Disabled means "unreachable given the other settings", which is itself part of the lesson.
 */
export default function ToggleGroup<T extends string | number | boolean>({
  label,
  options,
  value,
  onChange,
  disabled = false,
}: Props<T>) {
  return (
    <div className={disabled ? 'ctl disabled' : 'ctl'}>
      <div className="ctl-label">{label}</div>
      <div className="seg" role="group" aria-label={label}>
        {options.map((opt) => (
          <button
            key={String(opt.value)}
            type="button"
            aria-pressed={opt.value === value}
            disabled={disabled}
            onClick={() => onChange(opt.value)}
          >
            {opt.label}
          </button>
        ))}
      </div>
    </div>
  );
}
