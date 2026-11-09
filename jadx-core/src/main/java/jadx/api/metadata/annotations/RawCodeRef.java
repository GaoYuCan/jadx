package jadx.api.metadata.annotations;

import jadx.api.metadata.ICodeAnnotation;

/** Source reference to a symbol whose declaring class was not loaded. */
public final class RawCodeRef implements ICodeAnnotation {
	public enum Kind { CLASS, METHOD, FIELD }

	private final Kind kind;
	private final String owner;
	private final String member;
	private final String descriptor;

	public RawCodeRef(Kind kind, String owner, String member, String descriptor) {
		this.kind = kind;
		this.owner = owner;
		this.member = member;
		this.descriptor = descriptor;
	}

	public Kind getKind() {
		return kind;
	}

	public String getOwner() {
		return owner;
	}

	public String getMember() {
		return member;
	}

	public String getDescriptor() {
		return descriptor;
	}

	@Override
	public AnnType getAnnType() {
		return AnnType.RAW_REF;
	}
}
