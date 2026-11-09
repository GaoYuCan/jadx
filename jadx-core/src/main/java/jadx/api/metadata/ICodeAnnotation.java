package jadx.api.metadata;

public interface ICodeAnnotation {

	enum AnnType {
		CLASS,
		FIELD,
		METHOD,
		PKG,
		VAR,
		VAR_REF,
		DECLARATION,
		OFFSET,
		END, // class or method body end
		RAW_REF // symbol without a loaded declaration node
	}

	AnnType getAnnType();
}
