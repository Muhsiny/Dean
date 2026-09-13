(()=>{
  const dayDefs=[
    {day:1,skill:'Language systems',kind:'Learn',title:'Concept Workshop',minutes:25,xp:25},
    {day:2,skill:'Listening',kind:'Listen',title:'Listening Mission',minutes:25,xp:30},
    {day:3,skill:'Vocabulary',kind:'Vocabulary',title:'Word Power Lab',minutes:20,xp:25},
    {day:4,skill:'Accuracy',kind:'Grammar',title:'Accuracy Lab',minutes:25,xp:30},
    {day:5,skill:'Reading',kind:'Reading',title:'Reading Challenge',minutes:30,xp:35},
    {day:6,skill:'Production',kind:'Speak & Write',title:'Production Studio',minutes:35,xp:40},
    {day:7,skill:'Integrated mastery',kind:'Mastery',title:'Weekly Mission',minutes:30,xp:50}
  ];
  const focus=[
    ['Identity & introductions','exchange basic personal information confidently'],
    ['Family & possession','describe people, relationships and belongings'],
    ['Daily routines','describe habits, frequency and daily schedules'],
    ['Survival interaction','ask for help, directions, prices and services politely'],
    ['Home & community','describe places, facilities and what is available'],
    ['Food & shopping','handle quantities, prices and comparisons in shops'],
    ['Past events','tell a clear story about a completed event'],
    ['Future plans','distinguish plans, arrangements and predictions'],
    ['Connected ideas','link reasons, contrasts and results clearly'],
    ['Stories & experiences','combine background actions with key events'],
    ['Opinions','state, support and respectfully challenge viewpoints'],
    ['Communication repair','clarify, paraphrase and confirm meaning'],
    ['Workplace English','describe routine duties and temporary projects'],
    ['Health & services','explain problems, duration, severity and requested help'],
    ['Travel problems','handle delays, alternatives and indirect questions'],
    ['Learning strategies','explain study choices and evidence of progress'],
    ['News & information','separate source, evidence and claim in summaries'],
    ['Meetings','turn discussion into actions, owners and deadlines'],
    ['Presentations','present claims with evidence, structure and limits'],
    ['Negotiation','compare options, constraints and trade-offs'],
    ['Nuanced argument','qualify claims and respond to counterarguments'],
    ['Formal writing','produce concise recommendations and professional messages'],
    ['Professional fluency','manage complex workplace interaction and repair'],
    ['Integrated performance','transfer all skills to unfamiliar tasks']
  ];
  const levelForWeek=w=>w<=6?'A1':w<=12?'A2':w<=18?'B1':'B2';
  const canDo=(w,day)=>{
    const base=focus[w-1]?.[1]||'use English for the target situation';
    const verbs={1:'understand and apply the core language needed to',2:'identify key details and meaning while listening in order to',3:'retrieve and use high-value vocabulary to',4:'produce accurate forms while trying to',5:'extract, infer and summarize information in order to',6:'speak and write independently to',7:'combine listening, reading, speaking and writing to'};
    return `Can ${verbs[day]} ${base}.`;
  };
  const nodes=[];
  for(let w=1;w<=24;w++){
    for(const d of dayDefs){
      const wk=window.BEHESHTI_CURRICULUM?.[w-1]||{};
      nodes.push({
        id:`w${w}d${d.day}`,
        week:w,
        day:d.day,
        level:levelForWeek(w),
        theme:focus[w-1]?.[0]||wk.title||`Week ${w}`,
        title:`${d.title}: ${focus[w-1]?.[0]||wk.title||`Week ${w}`}`,
        skill:d.skill,
        kind:d.kind,
        minutes:d.minutes,
        xp:d.xp,
        canDo:canDo(w,d.day),
        outcome:wk.outcome||focus[w-1]?.[1]||'',
        masteryRequired:d.day===7
      });
    }
  }
  window.BEHESHTI_LESSON_GRAPH=nodes;
  window.BEHESHTI_WEEK_FOCUS=focus;
})();
