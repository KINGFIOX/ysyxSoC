/// A parsed command line: command name + remaining arguments string.
pub struct Command {
    pub name: String,
    pub args: String,
}

/// Parse a raw input line into a Command.
/// The first whitespace-delimited token becomes `name`; everything after it (trimmed) becomes `args`.
pub fn parse(input: &str) -> Option<Command> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return None;
    }

    let (name, args) = match trimmed.find(char::is_whitespace) {
        Some(pos) => (&trimmed[..pos], trimmed[pos..].trim_start()),
        None => (trimmed, ""),
    };

    Some(Command {
        name: name.to_string(),
        args: args.to_string(),
    })
}
