package com.jobscout.domain

object ResumeParser {
    val skillDictionary = listOf("Kotlin", "Java", "JavaScript", "TypeScript", "Python", "C++", "C#", ".NET", "SQL", "VBA", "Excel", "Power BI", "React", "Angular", "Vue", "Node.js", "Spring", "Android", "Compose", "Flutter", "Dart", "AWS", "Azure", "Docker", "Kubernetes", "Git", "PostgreSQL", "MySQL", "MongoDB", "HTML", "CSS", "Selenium", "Terraform", "Figma", "SAP", "Salesforce", "Scrum", "Linux")
    fun skillsIn(text: String): List<String> {
        val matcher = TermMatcher(text)
        return skillDictionary.filter(matcher::contains)
    }

    fun parse(text: String): ResumeDraft {
        require(text.length <= 300_000) { "Currículo muito extenso. Use um PDF com até 300 mil caracteres." }
        require(text.count { it.isLetter() } >= 40) { "PDF sem texto suficiente. Arquivos digitalizados precisam de OCR, ainda não disponível." }
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        fun section(headers: Set<String>): String {
            val start = lines.indexOfFirst { normalized(it).trimEnd(':') in headers }
            if (start < 0) return ""
            val allHeaders = setOf("experiencia", "experiencia profissional", "professional experience", "work experience", "experience", "educacao", "formacao", "formacao academica", "education", "projetos", "projects", "cursos", "courses", "habilidades", "skills", "idiomas", "languages", "resumo", "summary", "objetivo", "objective", "certificacoes", "certifications")
            return lines.drop(start + 1).takeWhile { normalized(it).trimEnd(':') !in allHeaders }.joinToString("\n").take(12_000)
        }
        val experience = section(setOf("experiencia", "experiencia profissional", "professional experience", "work experience", "experience"))
        val education = section(setOf("educacao", "formacao", "formacao academica", "education"))
        val languages = listOf("Português", "Inglês", "Espanhol", "English", "Spanish", "French", "Francês", "German", "Alemão").filter { containsTerm(text, it) }
        return ResumeDraft(CandidateProfile(skillsIn(text), experience, education = education, languages = languages), listOf(
            "Sugestões por palavras e cabeçalhos; revise todos os campos antes de salvar.",
            "Habilidades encontradas podem vir de cursos ou projetos. Não comprovam experiência profissional.",
            "Experiência e formação só são sugeridas quando há cabeçalhos reconhecidos. Objetivos são preenchidos por você.",
        ))
    }
}
